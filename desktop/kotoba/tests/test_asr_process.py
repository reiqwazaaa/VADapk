import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from kotoba import asr_process
from kotoba.core import Cancelled, Job, run_job


class IsolatedRecognitionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'audio.mp4'; self.source.touch()
        self.job = Job(self.source, self.root / 'out', language='en', target='en', translator='none')
        self.cancel = threading.Event()
        self.logs = []

    def command(self, body):
        script = self.root / 'fake native worker.py'
        script.write_text("import json,os,sys,time\nfrom pathlib import Path\nevents=Path(sys.argv[2]).open('a',encoding='utf-8',buffering=1)\ndef emit(**values):\n events.write(json.dumps(values)+'\\n');events.flush()\n"+body, 'utf-8')
        return patch('kotoba.asr_process.worker_command', side_effect=lambda req, events, fault: [sys.executable, str(script), str(req), str(events), str(fault)])

    def test_native_crash_preserves_already_exported_text(self):
        body = "emit(event='segment',start=0,end=2,text='First phrase')\nemit(event='segment',start=2,end=4,text='Last phrase')\nos._exit(73)\n"
        with self.command(body):
            with self.assertRaisesRegex(RuntimeError, '0x00000049'):
                run_job(self.job, self.cancel, self.logs.append)
        txt = list(self.job.output.rglob('*.txt')); srt = list(self.job.output.rglob('*.srt'))
        self.assertEqual(len(txt), 1); self.assertEqual(len(srt), 1)
        self.assertEqual(txt[0].read_text('utf-8-sig').strip(), 'First phrase\nLast phrase')
        self.assertIn('00:00:02,000 --> 00:00:04,000', srt[0].read_text('utf-8-sig'))
        self.assertFalse(any('Готово:' in line for line in self.logs))

    def test_output_exists_before_worker_finishes(self):
        body = "emit(event='segment',start=0,end=2,text='Checkpoint')\ntime.sleep(.5)\nemit(event='done',count=1,duration=2)\n"
        found = []
        def log(line):
            self.logs.append(line)
            if 'Промежуточно сохранено' in line:
                found.append(next(self.job.output.rglob('*.srt')).read_text('utf-8-sig'))
        with self.command(body): paths=run_job(self.job,self.cancel,log)
        self.assertTrue(found); self.assertIn('Checkpoint', found[0]); self.assertEqual(len(paths),2)

    def test_complete_message_before_cleanup_crash_is_valid(self):
        body = "emit(event='segment',start=0,end=2,text='Complete')\nemit(event='done',count=1,duration=2)\nos._exit(73)\n"
        with self.command(body):paths=run_job(self.job,self.cancel,self.logs.append)
        self.assertEqual(len(paths),2)
        self.assertTrue(any('подтвердил завершение' in line for line in self.logs))
        self.assertTrue(any('Готово:' in line for line in self.logs))

    def test_missing_completion_even_with_zero_exit_is_not_success(self):
        with self.command("emit(event='segment',start=0,end=1,text='Partial')\nos._exit(0)\n"):
            with self.assertRaisesRegex(RuntimeError,'без подтверждения'):
                run_job(self.job,self.cancel,self.logs.append)
        self.assertTrue(list(self.job.output.rglob('*.txt')))

    def test_cancellation_terminates_worker_and_preserves_text(self):
        body = "emit(event='segment',start=0,end=1,text='Keep me')\ntime.sleep(60)\n"
        def log(line):
            self.logs.append(line)
            if 'Промежуточно сохранено' in line:self.cancel.set()
        started=time.monotonic()
        with self.command(body):
            with self.assertRaises(Cancelled):run_job(self.job,self.cancel,log)
        self.assertLess(time.monotonic()-started,10)
        self.assertIn('Keep me',next(self.job.output.rglob('*.txt')).read_text('utf-8-sig'))

    def test_truncated_event_does_not_erase_previous_segments(self):
        body = "emit(event='segment',start=0,end=1,text='Safe')\nevents.write('{\"event\":');events.flush()\nos._exit(74)\n"
        with self.command(body):
            with self.assertRaises(RuntimeError):run_job(self.job,self.cancel,self.logs.append)
        self.assertIn('Safe',next(self.job.output.rglob('*.txt')).read_text('utf-8-sig'))

    def test_child_reported_python_error_keeps_diagnostic(self):
        body="emit(event='error',message='model failed',traceback='Child traceback: model failed')\nos._exit(1)\n"
        with self.command(body):
            with self.assertRaisesRegex(RuntimeError,'model failed'):run_job(self.job,self.cancel,self.logs.append)
        self.assertIn('Child traceback: model failed',self.logs)
        self.assertEqual(list(self.job.output.iterdir()),[])

    def test_worker_skips_destructors_and_clamps_padding_timestamps(self):
        script=self.root/'worker finalizer test.py'
        script.write_text("""import os,sys,types
from pathlib import Path
from kotoba import asr_worker
class Model:
 def __init__(self,*a,**kw):pass
 def __del__(self):os._exit(79)
 def transcribe(self,*a,**kw):
  chunks=[types.SimpleNamespace(start=0,end=2,text='One'),types.SimpleNamespace(start=3,end=8,text='Tail'),types.SimpleNamespace(start=8,end=9,text='Padding')]
  return iter(chunks),types.SimpleNamespace(duration=5)
sys.modules['faster_whisper']=types.SimpleNamespace(WhisperModel=Model)
sys.modules['ctranslate2']=types.SimpleNamespace(get_cuda_device_count=lambda:1)
asr_worker.main(*sys.argv[1:4])
""",'utf-8')
        request=self.root/'request.json';events=self.root/'events.jsonl';fault=self.root/'fault.txt'
        request.write_text(json.dumps({'source':'audio','model':'tiny','language':'en','device':'auto','models_dir':str(self.root/'models'),'offline_only':True}))
        env=os.environ.copy()
        env['PYTHONPATH']=str(Path(__file__).resolve().parents[1])+os.pathsep+env.get('PYTHONPATH','')
        process=subprocess.run([sys.executable,str(script),str(request),str(events),str(fault)],timeout=15,capture_output=True,env=env)
        self.assertEqual(process.returncode,0,process.stderr.decode(errors='replace'))
        records=[json.loads(line) for line in events.read_text().splitlines()]
        parts=[r for r in records if r['event']=='segment']
        self.assertEqual(len(parts),2);self.assertEqual(parts[-1]['end'],5)
        self.assertEqual(records[-1]['event'],'done');self.assertEqual(records[-1]['count'],2)

if __name__=='__main__':unittest.main()
