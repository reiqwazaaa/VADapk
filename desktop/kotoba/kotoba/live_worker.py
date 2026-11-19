"""Persistent live ASR/translation workers, each with a separate native lifetime."""
from __future__ import annotations
import faulthandler
import json
import os
from pathlib import Path
import queue
import sys
import time
import traceback

_LIVE = []


def terminate(events, code):
    events.flush()
    if os.name == 'nt':
        import ctypes
        kernel = ctypes.WinDLL('kernel32', use_last_error=True)
        kernel.GetCurrentProcess.restype = ctypes.c_void_p
        kernel.TerminateProcess.argtypes = [ctypes.c_void_p, ctypes.c_uint]
        kernel.TerminateProcess(kernel.GetCurrentProcess(), code)
    os._exit(code)


def entry(mode, request_path, event_path, fault_path):
    events = Path(event_path).open('a', encoding='utf-8', buffering=1)
    fault = Path(fault_path).open('a', encoding='utf-8', buffering=1)
    _LIVE.extend([events, fault])
    faulthandler.enable(fault, all_threads=True)
    def emit(event, **data):
        events.write(json.dumps({'event': event, **data}, ensure_ascii=True) + '\n'); events.flush()
    try:
        config = json.loads(Path(request_path).read_text('utf-8'))
        if mode == 'asr': run_asr(config, emit)
        else: run_translation(config, emit)
        emit('done'); terminate(events, 0)
    except BaseException as error:
        emit('error', message=str(error), traceback=traceback.format_exc())
        terminate(events, 1)


def run_asr(config, emit):
    from . import core
    from .live_audio import Capture, Utterance, RATE
    from .live_text import Agreement
    import numpy as np
    from faster_whisper import WhisperModel
    import ctranslate2
    device = config['device']
    if device == 'auto': device = 'cuda' if ctranslate2.get_cuda_device_count() else 'cpu'
    emit('log', message=f"Живой Whisper {config['model']} • {device.upper()}; загрузка модели…")
    model = WhisperModel(config['model'], device=device,
        compute_type='int8_float16' if device == 'cuda' else 'int8', cpu_threads=4,
        download_root=str(Path(config['models_dir'])/'whisper'), local_files_only=config['offline_only'])
    _LIVE.append(model)
    emit('ready', device=device)
    stop = Path(config['stop']); go = Path(config['go'])
    while not go.exists():
        if stop.exists(): return
        time.sleep(.05)
    engine = Utterance(config['profile'], config['threshold']); agreement = Agreement()
    last_preview = []

    def decode(request):
        nonlocal last_preview
        began = time.monotonic()
        chunks, _ = model.transcribe(request.audio, language=config['language'], task='transcribe',
            beam_size=1 if config['profile']=='Быстро' else 3, condition_on_previous_text=False,
            vad_filter=True, word_timestamps=request.forced)
        found = list(chunks); length = len(request.audio)/RATE
        rows = []
        consumed = engine.end
        if request.final and request.forced:
            cutoff = max(0, length - .7)
            for item in found:
                words = [w for w in (item.words or []) if w.end <= cutoff]
                if words:
                    rows.append({'start': request.start + max(0, words[0].start),
                                 'end': request.start + min(length, words[-1].end),
                                 'text': ''.join(w.word for w in words).strip()})
            if rows and rows[-1]['end'] > request.start + .3:
                consumed = rows[-1]['end']
            else:
                rows = []  # fallback when alignment is unavailable
        if not rows:
            for item in found:
                begin, end = max(0,float(item.start)), min(length,float(item.end))
                if begin < end and item.text.strip():
                    rows.append({'start':request.start+begin,'end':request.start+end,'text':item.text.strip()})
        text = ' '.join(item['text'] for item in rows)
        if request.final:
            if text:
                emit('commit', start=rows[0]['start'], end=rows[-1]['end'], text=text)
            emit('preview', text='', stable='', unstable='')
            agreement.clear(); last_preview=[]
            engine.commit(consumed if request.forced else None)
        else:
            last_preview=rows
            stable, unstable=agreement.update(text)
            emit('preview', text=text, stable=stable, unstable=unstable)
        emit('timing', inference=time.monotonic()-began, audio_end=engine.end)

    if config['source']=='file':
        emit('listening',source='file')  # deterministic CI/replay path, no audio device required
        from faster_whisper.audio import decode_audio
        audio = decode_audio(config['source_file'], sampling_rate=RATE)
        for index in range(0,len(audio),RATE//10):
            if stop.exists(): break
            request=engine.feed(index/RATE,audio[index:index+RATE//10])
            if request: decode(request)
        request=engine.finish()
        if request: decode(request)
        return
    capture=Capture(config['device_id'],stop); _LIVE.append(capture); capture.start()
    while not capture.ready.wait(.05):
        if capture.error is not None:raise RuntimeError(f'Ошибка открытия устройства: {capture.error}')
        if stop.exists():capture.stop.set();return
    emit('listening',source=config['source'])
    dropped=0
    def accept(offset,block,finishing=False):
        nonlocal last_preview
        if offset > engine.end + .25:
            gap=offset-engine.end
            emit('gap', seconds=gap)
            if last_preview:
                emit('commit',start=last_preview[0]['start'],end=last_preview[-1]['end'],text=' '.join(x['text'] for x in last_preview))
            engine.commit();agreement.clear();last_preview=[]
        request=engine.feed(offset,block)
        if request and (not finishing or request.final):decode(request)
    while not stop.exists():
        if capture.error is not None:raise RuntimeError(f'Ошибка захвата звука: {capture.error}')
        try:offset,block=capture.frames.get(timeout=.1)
        except queue.Empty:continue
        accept(offset,block)
        if capture.dropped != dropped:
            dropped=capture.dropped
            emit('log',message='Обработка не успевает за звуком. Выбери small или профиль «Быстро». Пропуски захвата отмечаются в журнале.')
        emit('level',rms=float(np.sqrt(np.mean(block*block))) if len(block) else 0,lag=max(0,capture.frames.qsize()*.1))
    capture.stop.set();capture.thread.join(timeout=.3)
    while True:
        try:offset,block=capture.frames.get_nowait()
        except queue.Empty:break
        accept(offset,block,True)
    request=engine.finish()
    if request:decode(request)


def run_translation(config, emit):
    from .core import Job, OfflineTranslator, OnlineTranslator
    job=Job(Path('.'),Path('.'),language=config['language'],target=config['target'],
            translator=config['translator'],api_key=os.environ.pop('KOTOBA_LIVE_API_KEY',''),
            deepl_free=config['deepl_free'],models_dir=Path(config['models_dir']),offline_only=config['offline_only'])
    translator = OfflineTranslator(job,lambda s:emit('log',message=s.replace('Whisper уже освобождён из памяти.','Перевод работает параллельно на CPU.'))) if job.translator=='offline' else OnlineTranslator(job,timeout=(5,12))
    _LIVE.append(translator)
    emit('ready')
    stop=Path(config['stop']); inbox=Path(config['inbox']);pending=b''; todo=[]
    with inbox.open('rb') as reader:
        while not stop.exists():
            pending+=reader.read()
            while b'\n' in pending:
                line,pending=pending.split(b'\n',1)
                if line.strip():todo.append(json.loads(line))
            if len(todo)>4:
                for item in todo[:-4]:emit('skipped',index=item['index'])
                todo=todo[-4:]
            if not todo:time.sleep(.05);continue
            item=todo.pop(0)
            try:
                text=translator.translate(item['text']).strip()
                if not text:raise RuntimeError('Пустой перевод')
                emit('translation',index=item['index'],text=text)
            except Exception as error:
                emit('translation_error',index=item['index'],message=str(error).replace(job.api_key,'[ключ скрыт]') if job.api_key else str(error))
