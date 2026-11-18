"""Parent-side ASR controller. Native crashes never terminate the application."""
from __future__ import annotations
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

from .core import Cancelled, Segment


class RecognitionError(RuntimeError):
    pass


def worker_command(request, events, fault):
    if getattr(sys, 'frozen', False):
        return [sys.executable, '--asr-worker', str(request), str(events), str(fault)]
    return [sys.executable, '-m', 'kotoba.asr_worker', str(request), str(events), str(fault)]


def stop_process(process):
    if process.poll() is not None: return
    process.terminate()
    try: process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def recognize(job, cancel, log, on_segment=None):
    with tempfile.TemporaryDirectory(prefix='kotoba-asr-') as temp:
        folder = Path(temp)
        request, events, fault = (folder / name for name in ('request.json', 'events.jsonl', 'native-stack.log'))
        request.write_text(json.dumps({
            'source': str(job.source), 'model': job.model, 'language': job.language,
            'device': job.device, 'models_dir': str(job.models_dir.expanduser().resolve()),
            'offline_only': job.offline_only,
        }), 'utf-8')
        events.touch()
        env = os.environ.copy()
        if not getattr(sys, 'frozen', False):
            env['PYTHONPATH'] = str(Path(__file__).resolve().parent.parent) + os.pathsep + env.get('PYTHONPATH', '')
        options = {'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}
        segments, errors = [], []
        done_count = None
        pending = b''
        stderr_path = folder / 'worker-stderr.log'
        with stderr_path.open('wb') as stderr, events.open('rb') as reader:
            process = subprocess.Popen(worker_command(request, events, fault), env=env,
                                       stdin=subprocess.DEVNULL, stdout=stderr, stderr=stderr, **options)
            try:
                log(f'Отдельный процесс Whisper: PID {process.pid}. TXT/SRT сохраняются по мере распознавания.')
                def drain():
                    nonlocal pending, done_count
                    pending += reader.read()
                    while b'\n' in pending:
                        line, pending = pending.split(b'\n', 1)
                        if not line.strip(): continue
                        try: item = json.loads(line.decode('utf-8'))
                        except (ValueError, UnicodeError) as error:
                            raise RecognitionError('Повреждено сообщение процесса Whisper.') from error
                        event = item.get('event')
                        if event == 'segment':
                            segment = Segment(float(item['start']), float(item['end']), item['text'])
                            segments.append(segment)
                            if on_segment: on_segment(segment)
                        elif event == 'log': log(item['message'])
                        elif event == 'info':
                            log(f"Длительность аудио: {item['duration']:.2f} сек.; устройство: {item['device']}.")
                        elif event == 'done': done_count = int(item['count'])
                        elif event == 'error': errors.append(item)
                while True:
                    drain()
                    code = process.poll()
                    if code is not None:
                        drain()
                        break
                    if cancel.wait(.05):
                        stop_process(process)
                        drain()
                        raise Cancelled('Распознавание отменено; полученные фразы сохранены.')
                if errors:
                    for error in errors: log(error.get('traceback', error['message']))
                    raise RecognitionError(errors[-1]['message'])
                if done_count is not None and done_count == len(segments):
                    if code != 0:
                        log(f'Whisper подтвердил завершение, но его процесс вышел с кодом 0x{code & 0xffffffff:08X}. Полученный текст сохранён.')
                    log(f'Распознавание завершено: {len(segments)} фраз. Процесс Whisper завершён.')
                    return segments
                diagnostics = []
                for path in (fault, stderr_path):
                    if path.is_file():
                        detail = path.read_text('utf-8', errors='replace')[-24000:].strip()
                        if detail: diagnostics.append(detail)
                for detail in diagnostics: log('Диагностика дочернего процесса:\n' + detail)
                raise RecognitionError(
                    f'Процесс Whisper аварийно завершился без подтверждения окончания '
                    f'(код 0x{code & 0xffffffff:08X}). Получено {len(segments)} фраз. '
                    'Окно приложения продолжает работать; полученный текст сохранён.')
            finally:
                stop_process(process)
