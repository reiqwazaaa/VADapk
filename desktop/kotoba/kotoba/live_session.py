"""Live session controller: no native audio/model calls run in the GUI process."""
from __future__ import annotations
from dataclasses import dataclass, replace
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import time
from contextlib import suppress
from .core import Segment, export, prepare_output, new_result_folder, timestamp, verify_exports
from .asr_process import stop_process


@dataclass
class LiveConfig:
    output: Path
    models_dir: Path
    language: str='ja'
    target: str='ru'
    model: str='large-v3-turbo'
    device: str='auto'
    translator: str='none'
    api_key: str=''
    deepl_free: bool=True
    offline_only: bool=False
    source: str='system'
    device_id: int=-1
    profile: str='Баланс'
    threshold: float=.004
    source_file: str=''


def command(flag, *args):
    if getattr(sys,'frozen',False):return [sys.executable,flag,*map(str,args)]
    return [sys.executable,'-m','kotoba.live_cli',flag,*map(str,args)]


def process_options():
    env=os.environ.copy()
    if not getattr(sys,'frozen',False):
        env['PYTHONPATH']=str(Path(__file__).resolve().parent.parent)+os.pathsep+env.get('PYTHONPATH','')
    options={'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}
    return env,options


def list_devices():
    with tempfile.TemporaryDirectory(prefix='kotoba-devices-') as temp:
        report=Path(temp)/'devices.json';env,options=process_options()
        process=subprocess.run(command('--live-devices',report),stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,env=env,timeout=20,**options)
        if not report.exists():raise RuntimeError(f'Не удалось опросить аудиоустройства (код {process.returncode}).')
        result=json.loads(report.read_text('utf-8'))
        if not result['ok']:raise RuntimeError(result['error'])
        return result['devices']


class Worker:
    def __init__(self, folder, kind, config):
        self.path=folder/kind;self.path.mkdir()
        self.events=self.path/'events.jsonl';self.events.touch()
        self.reader=self.events.open('rb');self.pending=b''
        assert 'api_key' not in config and '_key' not in config
        request=self.path/'request.json';request.write_text(json.dumps(config),'utf-8')
        self.stderr=(self.path/'stderr.log').open('wb')
        env,options=process_options()
        # A key is passed only through process memory/environment, never the JSON request/log.
        assert 'api_key' not in config and '_key' not in config
        # Callers pass credentials separately after request serialization (see spawn below).
        self.process=None
        self.env,self.options,self.request=env,options,request
    def start(self,kind,key=''):
        if key:self.env['KOTOBA_LIVE_API_KEY']=key
        self.process=subprocess.Popen(command('--live-worker',kind,self.request,self.events,self.path/'fault.log'),
            stdin=subprocess.DEVNULL,stdout=self.stderr,stderr=self.stderr,env=self.env,**self.options)
    def read(self):
        self.pending+=self.reader.read();items=[]
        while b'\n' in self.pending:
            line,self.pending=self.pending.split(b'\n',1)
            if line.strip():items.append(json.loads(line))
        return items
    def close(self,log):
        if self.process is not None:
            stop_process(self.process)
            code=self.process.returncode
            if code:
                log(f'Код live worker: 0x{code & 0xffffffff:08X}')
                for name in ('fault.log','stderr.log'):
                    p=self.path/name
                    if p.exists():
                        text=p.read_text('utf-8',errors='replace')[-16000:].strip()
                        if text:log(text)
        self.reader.close();self.stderr.close()


class SessionWriter:
    def __init__(self,config,log):
        self.config=config;self.log=log
        parent=config.output.expanduser().resolve();budget=prepare_output(parent)
        self.folder=new_result_folder(parent,'Live_'+time.strftime('%Y%m%d_%H%M%S'),budget)
        self.original=[];self.translated={};self.skipped=[];self.gaps=[]
        self.draft=self.folder/'preview.txt'
        self.paths=[];self.closed=False
        self.metadata={'version':1,'status':'preparing','source':config.source,'language':config.language,
                       'target':config.target,'translator':config.translator,'capture_gaps':self.gaps,
                       'translation_skipped':self.skipped}
        self.status('preparing')
    def status(self,value):
        self.metadata.update(status=value,segments=len(self.original),translations=len(self.translated))
        temp=self.folder/'session.json.tmp';temp.write_text(json.dumps(self.metadata,ensure_ascii=False,indent=2),'utf-8')
        temp.replace(self.folder/'session.json')
    def commit(self,item):
        segment=Segment(float(item['start']),float(item['end']),item['text'].strip())
        if segment.end<=segment.start or not segment.text:return None
        self.original.append(segment)
        self.append_segment(segment,self.config.language,len(self.original))
        self.status('listening');self.log(f'Сохранена фраза #{len(self.original)}: {segment.start:.1f}–{segment.end:.1f} сек.')
        return len(self.original)-1
    def append_segment(self,segment,language,number):
        entries=(('txt',segment.text+'\n'),('srt',f'{number}\n{timestamp(segment.start)} --> {timestamp(max(segment.end,segment.start+.001))}\n{segment.text}\n\n'))
        for extension,text in entries:
            path=self.folder/f'live.{language}.{extension}'
            if not path.exists():path.write_bytes(b'\xef\xbb\xbf')
            raw=text.encode('utf-8')
            with path.open('ab') as file:
                file.write(raw);file.flush();os.fsync(file.fileno())
            with path.open('rb') as file:
                file.seek(-len(raw),os.SEEK_END)
                if file.read()!=raw:raise RuntimeError(f'Не удалось проверить запись {path}')
            if path not in self.paths:self.paths.append(path)
    def preview(self,text):
        if text:
            temp=self.draft.with_suffix('.tmp');temp.write_text(text,'utf-8-sig');temp.replace(self.draft)
        else:self.draft.unlink(missing_ok=True)
    def translation(self,index,text):
        if not 0<=index<len(self.original):raise ValueError('Некорректный номер перевода.')
        append = index not in self.translated and (not self.translated or index > max(self.translated))
        self.translated[index]=replace(self.original[index],text=text)
        if append:self.append_segment(self.translated[index],self.config.target,len(self.translated))
        else:
            records=[self.translated[i] for i in sorted(self.translated)]
            result=export(records,self.folder,'live',self.config.target)
            self.paths=[p for p in self.paths if f'.{self.config.target}.' not in p.name]+list(result)
        self.status('listening')
    def finish(self,ok):
        self.closed=True
        if self.paths:verify_exports(self.paths)
        self.status('stopped' if ok else 'interrupted')
        if ok:self.draft.unlink(missing_ok=True)
        self.log(f'Живая сессия сохранена: {self.folder}')


def run_live(config,stop,log,notify,worker_factory=Worker):
    if config.language!=config.target and config.translator in ('google','deepl') and not config.api_key.strip():
        raise ValueError('Для онлайн-перевода нужен API-ключ.')
    if config.offline_only and config.translator in ('google','deepl'):raise ValueError('Онлайн-перевод недоступен в строгом офлайн-режиме.')
    writer=SessionWriter(config,log);notify('folder',str(writer.folder))
    translate=config.translator!='none' and config.language!=config.target
    workers={};ok=False;last_target=-1;stopping=None;translation_pending=set()
    with tempfile.TemporaryDirectory(prefix='kotoba-live-') as temp:
        root=Path(temp);go=root/'go';asr_stop=root/'stop-asr';trans_stop=root/'stop-translate'
        inbox=root/'translate.jsonl';inbox.touch()
        watcher_done=threading.Event()
        def watch_stop():
            while not watcher_done.is_set():
                if stop.wait(.1):
                    with suppress(OSError):asr_stop.touch()
                    return
        watcher=threading.Thread(target=watch_stop,daemon=True);watcher.start()
        common={'models_dir':str(config.models_dir.expanduser().resolve()),'language':config.language,
                'target':config.target,'offline_only':config.offline_only}
        try:
            asr={**common,'model':config.model,'device':config.device,'source':config.source,
                 'device_id':config.device_id,'source_file':config.source_file,'profile':config.profile,
                 'threshold':config.threshold,'go':str(go),'stop':str(asr_stop)}
            workers['asr']=worker_factory(root,'asr',asr);workers['asr'].start('asr')
            if translate:
                trans={**common,'translator':config.translator,'deepl_free':config.deepl_free,
                       'inbox':str(inbox),'stop':str(trans_stop)}
                workers['translate']=worker_factory(root,'translate',trans);workers['translate'].start('translate',config.api_key)
            ready=set();done=set();started=False
            with inbox.open('a',encoding='utf-8',buffering=1) as requests:
                while True:
                    if stop.is_set() and stopping is None:
                        stopping=time.monotonic();asr_stop.touch()
                        log('Останавливаю захват и сохраняю последнюю фразу…');notify('status','Остановка…')
                    for kind,worker in workers.items():
                        items=worker.read()
                        code=worker.process.poll()
                        if code is not None: items+=worker.read()
                        for item in items:
                            event=item['event']
                            if event=='error':raise RuntimeError(item.get('traceback',item['message']))
                            if event=='ready':ready.add(kind)
                            elif event=='done':done.add(kind)
                            elif event=='log':log(item['message'])
                            elif kind=='asr' and event=='listening':
                                writer.status('listening');notify('status','Слушаю: системный звук' if config.source=='system' else 'Слушаю: микрофон' if config.source=='mic' else 'Тестовый аудиопоток')
                            elif event=='preview':writer.preview(item['text']);notify('preview',item)
                            elif event=='commit':
                                index=writer.commit(item)
                                if index is not None:
                                    notify('commit',{**item,'index':index})
                                    if translate:
                                        requests.write(json.dumps({'index':index,'text':item['text']},ensure_ascii=True)+'\n');requests.flush()
                                        translation_pending.add(index)
                            elif event=='translation':
                                index=item['index'];writer.translation(index,item['text']);translation_pending.discard(index)
                                if index>=last_target:
                                    last_target=index;notify('translation',{**item,'original':writer.original[index].text})
                            elif event in ('skipped','translation_error'):
                                index=item['index'];translation_pending.discard(index);writer.skipped.append(index)
                                log(f"Перевод фразы #{index+1} пропущен: {item.get('message','перевод не успевает; оригинал сохранён')}")
                                notify('warning','Перевод отстаёт или недоступен. Оригинал сохранён.')
                            elif event=='gap':
                                writer.gaps.append(item['seconds']);log(f"Пропуск захвата: {item['seconds']:.2f} сек. Уменьши модель/выбери «Быстро».")
                                notify('warning','Пропуск звука: обработка отстаёт. Выбери small или «Быстро».')
                            elif event in ('level','timing'):notify(event,item)
                        if code is not None and kind not in done:
                            raise RuntimeError(f'Живой {kind} worker завершился без подтверждения (0x{code & 0xffffffff:08X}). Полученный текст сохранён.')
                    if ready==set(workers) and not started and stopping is None:
                        go.touch();started=True;notify('status','Запускаю захват…')
                    if 'asr' in done:
                        if not translate or not translation_pending:break
                        if stopping is None:stopping=time.monotonic()
                    if stopping is not None and time.monotonic()-stopping>12:
                        if 'asr' not in done:log('Whisper остановлен принудительно. Последний черновик и подтверждённый текст сохранены.')
                        if translation_pending:log(f'Перевод не окончен для {len(translation_pending)} фраз; оригинал сохранён.')
                        break
                    time.sleep(.05)
                ok='asr' in done
                trans_stop.touch()
                if translation_pending:
                    writer.skipped.extend(sorted(translation_pending));writer.metadata['translation_incomplete']=True
        finally:
            watcher_done.set();watcher.join(timeout=.2)
            asr_stop.touch();trans_stop.touch()
            try:
                for worker in workers.values():
                    try:worker.close(log)
                    except Exception as error:log(f'Ошибка остановки worker: {error}')
            finally:
                writer.finish(ok)
                notify('summary',dict(writer.metadata))
    return writer.folder
