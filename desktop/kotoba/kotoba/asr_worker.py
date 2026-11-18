"""Isolated native ASR process. Never unload a live CUDA model in the GUI process."""
from __future__ import annotations
import faulthandler
import json
import math
import os
from pathlib import Path
import sys
import traceback

# Retain native objects until os._exit: Python finalizers must not unload CUDA DLLs.
_LIVE_MODEL = None
_FAULT_FILE = None


def main(request_path, events_path, fault_path):
    global _LIVE_MODEL, _FAULT_FILE
    events = Path(events_path).open('a', encoding='utf-8', buffering=1)

    def emit(event, **values):
        events.write(json.dumps({'event': event, **values}, ensure_ascii=True) + '\n')
        events.flush()

    def finish(code):
        events.flush()
        for stream in (sys.stdout, sys.stderr, _FAULT_FILE):
            if stream is not None:
                try: stream.flush()
                except Exception: pass
        if os.name == 'nt':
            # ExitProcess runs DLL detach callbacks; TerminateProcess skips them.
            # Only this dedicated worker is terminated, after every event is flushed.
            import ctypes
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            kernel.GetCurrentProcess.restype = ctypes.c_void_p
            kernel.TerminateProcess.argtypes = [ctypes.c_void_p, ctypes.c_uint]
            kernel.TerminateProcess(kernel.GetCurrentProcess(), code)
        os._exit(code)

    try:
        _FAULT_FILE = Path(fault_path).open('a', encoding='utf-8', buffering=1)
        faulthandler.enable(file=_FAULT_FILE, all_threads=True)
        request = json.loads(Path(request_path).read_text('utf-8'))
        from . import core  # register this process's CUDA DLL directories
        from faster_whisper import WhisperModel
        import ctranslate2
        device = request['device']
        if device == 'auto': device = 'cuda' if ctranslate2.get_cuda_device_count() else 'cpu'
        compute = 'int8_float16' if device == 'cuda' else 'int8'
        emit('log', message=f"Загрузка Whisper {request['model']} • {device.upper()} • {compute}.")
        _LIVE_MODEL = WhisperModel(request['model'], device=device, compute_type=compute,
            download_root=str(Path(request['models_dir']) / 'whisper'),
            local_files_only=request['offline_only'], cpu_threads=4)
        chunks, info = _LIVE_MODEL.transcribe(request['source'], language=request['language'],
            task='transcribe', vad_filter=True, beam_size=5, condition_on_previous_text=False)
        duration = float(info.duration)
        emit('info', duration=duration, device=device)
        count = 0
        for chunk in chunks:
            start, end = max(0.0, float(chunk.start)), float(chunk.end)
            if not math.isfinite(start) or not math.isfinite(end): continue
            if duration > 0:
                if start >= duration: continue
                end = min(end, duration)
            text = chunk.text.strip()
            if text and end > start:
                emit('segment', start=start, end=end, text=text)
                count += 1
            emit('log', message=f'Распознано {max(0.0, end):.0f} / {duration:.0f} сек.')
        emit('done', count=count, duration=duration)
        # The OS reclaims the entire CUDA context. Do not run native model/DLL finalizers.
        finish(0)
    except BaseException as error:
        emit('error', message=str(error), traceback=traceback.format_exc())
        finish(1)


if __name__ == '__main__':
    main(*sys.argv[1:4])
