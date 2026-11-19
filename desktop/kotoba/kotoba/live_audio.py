"""WASAPI capture is used only inside the isolated live worker."""
from __future__ import annotations
from dataclasses import dataclass
import queue
import threading
from pathlib import Path
from contextlib import suppress
import numpy as np

RATE = 16000
PROFILES = {'Быстро': (1.2, 6.0, .45), 'Баланс': (1.8, 9.0, .7), 'Контекст': (2.5, 14.0, .9)}


def devices():
    import pyaudiowpatch as pa
    result = []
    with pa.PyAudio() as audio:
        host = audio.get_host_api_info_by_type(pa.paWASAPI)['index']
        defaults={}
        with suppress(Exception):defaults['system']=audio.get_default_wasapi_loopback()['index']
        with suppress(Exception):defaults['mic']=audio.get_default_wasapi_device(d_in=True)['index']
        for item in audio.get_device_info_generator():
            if item['hostApi'] != host or item['maxInputChannels'] <= 0: continue
            kind='system' if item.get('isLoopbackDevice') else 'mic'
            result.append({'id': int(item['index']), 'kind': kind, 'default':item['index']==defaults.get(kind),
                           'name': item['name'], 'rate': int(item['defaultSampleRate']),
                           'channels': int(item['maxInputChannels'])})
    return sorted(result,key=lambda d:not d['default'])


class Capture:
    def __init__(self, device_id, stop_path=None):
        self.device_id = int(device_id)
        self.stop_path = Path(stop_path) if stop_path else None
        self.frames = queue.Queue(maxsize=30)  # about three seconds; latency cannot grow indefinitely
        self.stop = threading.Event()
        self.ready = threading.Event()
        self.error = None
        self.dropped = 0
        self.thread = threading.Thread(target=self._run, daemon=True)
    def start(self): self.thread.start()
    def _run(self):
        try:
            import pyaudiowpatch as pa
            import av
            with pa.PyAudio() as audio:
                info = audio.get_device_info_by_index(self.device_id)
                rate = int(info['defaultSampleRate']); channels = int(info['maxInputChannels'])
                if not 1 <= channels <= 32: raise ValueError('Устройство не поддерживает захват звука.')
                resampler = av.AudioResampler(format='fltp', layout='mono', rate=RATE)
                index = 0
                with audio.open(format=pa.paInt16, channels=channels, rate=rate, input=True,
                                input_device_index=self.device_id, frames_per_buffer=max(1, rate // 10)) as stream:
                    self.ready.set()
                    while not self.stop.is_set() and not (self.stop_path and self.stop_path.exists()):
                        raw = stream.read(max(1, rate // 10), exception_on_overflow=False)
                        if self.stop_path and self.stop_path.exists(): break
                        pcm = np.frombuffer(raw, dtype='<i2').reshape(-1, channels).astype(np.float32).mean(axis=1) / 32768.0
                        frame = av.AudioFrame.from_ndarray(pcm.reshape(1, -1), format='flt', layout='mono')
                        frame.sample_rate = rate
                        for converted in resampler.resample(frame):
                            block = converted.to_ndarray().reshape(-1).copy()
                            packet = (index / RATE, block)
                            index += len(block)
                            try: self.frames.put_nowait(packet)
                            except queue.Full:
                                try: self.frames.get_nowait(); self.dropped += 1
                                except queue.Empty: pass
                                self.frames.put_nowait(packet)
        except BaseException as error:
            self.error = error


@dataclass
class DecodeRequest:
    audio: np.ndarray
    start: float
    final: bool
    forced: bool


class Utterance:
    """Bounded speech buffer with pause and duration boundaries."""
    def __init__(self, profile='Баланс', threshold=.004):
        self.step, self.maximum, self.pause = PROFILES[profile]
        self.threshold = threshold
        self.parts = []
        self.start = 0.0
        self.end = 0.0
        self.last_voice = 0.0
        self.last_decode = 0.0
        self.preroll = np.zeros(0, dtype=np.float32)
    def feed(self, offset, block):
        block = np.asarray(block, dtype=np.float32)
        end = offset + len(block) / RATE
        voice = bool(len(block) and float(np.sqrt(np.mean(block * block))) >= self.threshold)
        if not self.parts:
            if not voice:
                self.preroll = np.concatenate((self.preroll, block))[-RATE // 4:]
                self.end = end
                return None
            self.start = max(0, offset - len(self.preroll) / RATE)
            self.last_decode = self.start
            if len(self.preroll): self.parts.append(self.preroll)
            self.preroll = np.zeros(0, dtype=np.float32)
        self.parts.append(block)
        self.end = end
        if voice: self.last_voice = end
        forced = end - self.start >= self.maximum
        final = forced or end - self.last_voice >= self.pause
        if final or (end - self.last_decode >= self.step and end - self.start >= 1):
            self.last_decode = end
            return DecodeRequest(np.concatenate(self.parts), self.start, final, forced)
        return None
    def commit(self, consumed=None):
        # A word crossing the forced boundary is kept in the next audio window.
        if consumed is not None and consumed < self.end - .05:
            full = np.concatenate(self.parts)
            cut = min(len(full), max(1, round((consumed - self.start) * RATE)))
            self.parts = [full[cut:]] if cut < len(full) else []
            self.start = consumed
            self.last_decode = self.end
        else:
            self.parts = []
            self.start = self.end
            self.preroll = np.zeros(0, dtype=np.float32)
    def finish(self):
        if not self.parts: return None
        return DecodeRequest(np.concatenate(self.parts), self.start, True, False)
