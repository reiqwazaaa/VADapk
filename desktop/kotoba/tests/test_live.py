import json
from pathlib import Path
import tempfile
import threading
import types
import unittest
from unittest.mock import patch
import numpy as np
from kotoba.live_text import Agreement
from kotoba.live_audio import Utterance,RATE
from kotoba.live_session import LiveConfig,SessionWriter,run_live


class LiveAudioTests(unittest.TestCase):
    def test_english_agreement_does_not_freeze_changed_word(self):
        a=Agreement()
        self.assertEqual(a.update('Hello world'),('', 'Hello world'))
        self.assertEqual(a.update('Hello there'),('Hello ', 'there'))
        a.clear();self.assertEqual(a.update('Again'),('', 'Again'))
    def test_japanese_agreement_without_spaces(self):
        a=Agreement();a.update('今日は晴れ');self.assertEqual(a.update('今日は雨'),('今日は','雨'))
    def test_silence_does_not_produce_decode_requests(self):
        u=Utterance('Быстро')
        for i in range(100):self.assertIsNone(u.feed(i/10,np.zeros(RATE//10,dtype=np.float32)))
        self.assertIsNone(u.finish());self.assertLessEqual(len(u.preroll),RATE//4)
    def test_pause_finalizes_and_duration_is_bounded(self):
        u=Utterance('Быстро');requests=[]
        for i in range(25):
            block=np.full(RATE//10,.1 if i<15 else 0,dtype=np.float32)
            r=u.feed(i/10,block)
            if r:
                requests.append(r)
                if r.final:u.commit()
        self.assertTrue(any(not r.final for r in requests));self.assertTrue(any(r.final for r in requests))
        self.assertIsNone(u.finish())
    def test_forced_boundary_retains_unconfirmed_word_audio(self):
        u=Utterance('Быстро');final=None
        for i in range(61):
            r=u.feed(i/10,np.full(RATE//10,.1,dtype=np.float32))
            if r and r.forced:final=r;break
        self.assertIsNotNone(final)
        u.commit(final.start+5.3)
        self.assertAlmostEqual(u.start,5.3)
        self.assertGreater(len(np.concatenate(u.parts)),0)
        self.assertLess(len(np.concatenate(u.parts)),RATE)


class LiveSavingTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.config=LiveConfig(Path(self.temp.name)/'output',Path(self.temp.name)/'models')
    def test_session_persists_commits_and_translation_timing(self):
        w=SessionWriter(self.config,lambda s:None)
        w.commit({'start':1,'end':3,'text':'こんにちは'})
        self.assertIn('こんにちは',(w.folder/'live.ja.txt').read_text('utf-8-sig'))
        w.translation(0,'Привет')
        w.commit({'start':4,'end':5,'text':'世界'})
        w.translation(1,'Мир')
        w.finish(True)
        self.assertIn('00:00:04,000 --> 00:00:05,000\nМир',(w.folder/'live.ru.srt').read_text('utf-8-sig'))
        self.assertEqual((w.folder/'live.ja.txt').read_text('utf-8-sig').count('こんにちは'),1)
        self.assertEqual(json.loads((w.folder/'session.json').read_text())['status'],'stopped')
    def test_interrupted_session_keeps_unconfirmed_draft(self):
        w=SessionWriter(self.config,lambda s:None);w.preview('Черновик');w.finish(False)
        self.assertEqual(w.draft.read_text('utf-8-sig'),'Черновик')
        self.assertEqual(json.loads((w.folder/'session.json').read_text())['status'],'interrupted')
    def test_out_of_order_translations_are_exported_in_time_order(self):
        w=SessionWriter(self.config,lambda s:None)
        for i in range(3):w.commit({'start':i*2,'end':i*2+1,'text':str(i)})
        w.translation(2,'last');w.translation(0,'first')
        self.assertEqual((w.folder/'live.ru.txt').read_text('utf-8-sig').splitlines(),['first','last'])
    def test_key_validation_before_capture(self):
        self.config.translator='google'
        with self.assertRaisesRegex(ValueError,'API'):run_live(self.config,threading.Event(),lambda s:None,lambda *a:None)

    def test_crashed_live_worker_preserves_text_and_preview(self):
        created=[]
        class Fake:
            def __init__(worker,folder,kind,config):
                worker.kind=kind;worker.calls=0;worker.process=types.SimpleNamespace(poll=lambda:None,returncode=73)
                created.append(worker)
            def start(worker,*args):pass
            def read(worker):
                worker.calls+=1
                if worker.calls==1:return [{'event':'ready'}]
                if worker.calls==2:
                    worker.process.poll=lambda:73
                    return [{'event':'commit','start':0,'end':2,'text':'Saved speech'}, {'event':'preview','text':'Draft','stable':'','unstable':'Draft'}]
                return []
            def close(worker,log):worker.closed=True
        self.config.translator='none';events=[]
        with self.assertRaisesRegex(RuntimeError,'без подтверждения'):
            run_live(self.config,threading.Event(),lambda s:None,lambda e,d:events.append((e,d)),Fake)
        folder=Path(next(d for e,d in events if e=='folder'))
        self.assertIn('Saved speech',(folder/'live.ja.txt').read_text('utf-8-sig'))
        self.assertTrue((folder/'preview.txt').exists())
        self.assertTrue(all(w.closed for w in created))
    def test_last_events_are_not_lost_when_worker_exits(self):
        class Fake:
            def __init__(worker,folder,kind,config):
                worker.calls=0;worker.process=types.SimpleNamespace(poll=lambda:None,returncode=0)
            def start(worker,*args):pass
            def read(worker):
                worker.calls+=1
                if worker.calls==1:return [{'event':'ready'}]
                if worker.calls==2:
                    worker.process.poll=lambda:0
                    return [{'event':'commit','start':0,'end':1,'text':'Final record'},{'event':'done'}]
                return []
            def close(worker,log):pass
        self.config.translator='none'
        folder=run_live(self.config,threading.Event(),lambda s:None,lambda *a:None,Fake)
        self.assertIn('Final record',(folder/'live.ja.txt').read_text('utf-8-sig'))

if __name__=='__main__':unittest.main()

class CaptureConversionTests(unittest.TestCase):
    def test_stereo_capture_is_downmixed_and_resampled_without_real_device(self):
        try: import av
        except ImportError:self.skipTest('PyAV not installed in lightweight test environment')
        from kotoba.live_audio import Capture
        import sys
        capture=Capture(7);opened=[]
        class Stream:
            count=0
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def read(self,n,exception_on_overflow=False):
                self.count+=1
                if self.count==5:capture.stop.set()
                values=np.column_stack((np.full(n,3276),np.full(n,1638))).astype('<i2')
                return values.tobytes()
        class Audio:
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def get_device_info_by_index(self,index):return {'defaultSampleRate':48000,'maxInputChannels':2}
            def open(self,**kwargs):opened.append(kwargs);return Stream()
        fake=types.SimpleNamespace(PyAudio=Audio,paInt16=8)
        with patch.dict(sys.modules,{'pyaudiowpatch':fake}):capture._run()
        self.assertIsNone(capture.error)
        blocks=[]
        while not capture.frames.empty():blocks.append(capture.frames.get()[1])
        joined=np.concatenate(blocks)
        self.assertTrue(7800<len(joined)<=8000)
        self.assertAlmostEqual(float(np.mean(joined[100:])),.075,places=2)
        self.assertEqual(opened[0]['input_device_index'],7)
        self.assertTrue(opened[0]['input'])

class LiveTranslationControllerTests(unittest.TestCase):
    def test_capture_waits_for_translation_and_commits_are_translated(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);configs=[]
            config=LiveConfig(root/'out',root/'models',translator='google',api_key='private-key')
            class Fake:
                def __init__(self,folder,kind,cfg):
                    self.kind=kind;self.cfg=cfg;self.sent=False;self.ready=False;self.calls=0
                    self.process=types.SimpleNamespace(poll=lambda:None,returncode=0)
                    configs.append(cfg)
                def start(self,kind,key=''):
                    if kind=='translate':assert key=='private-key'
                def read(self):
                    self.calls+=1
                    if not self.ready:
                        if self.kind=='translate' and self.calls<3:return []
                        self.ready=True;return [{'event':'ready'}]
                    if self.kind=='asr' and not self.sent and Path(self.cfg['go']).exists():
                        self.sent=True
                        return [{'event':'commit','start':0,'end':2,'text':'Hello'},{'event':'done'}]
                    if self.kind=='translate' and not self.sent and Path(self.cfg['inbox']).stat().st_size:
                        self.sent=True;return [{'event':'translation','index':0,'text':'Привет'}]
                    return []
                def close(self,log):pass
            folder=run_live(config,threading.Event(),lambda s:None,lambda *a:None,Fake)
            self.assertIn('Привет',(folder/'live.ru.txt').read_text('utf-8-sig'))
            self.assertNotIn('private-key',json.dumps(configs))

    def test_stop_before_models_ready_never_starts_capture(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);stop=threading.Event();stop.set();checked=[]
            class Fake:
                def __init__(self,folder,kind,cfg):
                    self.cfg=cfg;self.sent=False;self.process=types.SimpleNamespace(poll=lambda:None,returncode=0)
                def start(self,*a):pass
                def read(self):
                    self.asserted=not Path(self.cfg['go']).exists();checked.append(self.asserted)
                    if Path(self.cfg['stop']).exists() and not self.sent:
                        self.sent=True;return [{'event':'done'}]
                    return []
                def close(self,log):pass
            run_live(LiveConfig(root/'out',root/'models'),stop,lambda s:None,lambda *a:None,Fake)
            self.assertTrue(checked);self.assertTrue(all(checked))
