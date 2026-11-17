from __future__ import annotations
import gc
import html
import json
import os
import re
import sys
import tempfile
import threading
import time
import unicodedata
import uuid
from contextlib import suppress
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Callable

LANGUAGES = {'Русский': 'ru', 'English': 'en', '日本語': 'ja'}
MODELS = {'large-v3-turbo': 'Баланс • RTX 3070: рекомендуем начать здесь',
          'large-v3': 'Качество • русский / японский, медленнее turbo',
          'medium': 'Меньше памяти • все три языка',
          'small': 'Скорость • английский, слабые ПК'}
NLLB = {'ru': 'rus_Cyrl', 'en': 'eng_Latn', 'ja': 'jpn_Jpan'}
class Cancelled(Exception): pass

def check_cancel(event):
    if event.is_set(): raise Cancelled('Операция отменена')

@dataclass(frozen=True)
class Segment:
    start: float
    end: float
    text: str

@dataclass
class Job:
    source: Path
    output: Path
    language: str = 'ja'
    target: str = 'ru'
    model: str = 'large-v3-turbo'
    device: str = 'auto'
    translator: str = 'offline'
    api_key: str = ''
    deepl_free: bool = True
    models_dir: Path = Path.home() / '.kotoba' / 'models'
    offline_only: bool = False


def timestamp(seconds: float) -> str:
    ms = max(0, round(seconds * 1000))
    hours, ms = divmod(ms, 3600000)
    minutes, ms = divmod(ms, 60000)
    secs, ms = divmod(ms, 1000)
    return f'{hours:02}:{minutes:02}:{secs:02},{ms:03}'


MAX_OUTPUT_PATH = 240


def path_units(value) -> int:
    return len(str(value).encode('utf-16-le')) // 2


def safe_stem(value: str, limit: int = 64) -> str:
    value = unicodedata.normalize('NFC', value)
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', value).strip(' .') or 'transcript'
    if re.match(r'^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)', value, re.I):
        value = '_' + value
    result = ''
    for char in value:
        if path_units(result + char) > limit: break
        result += char
    return result.rstrip(' .') or 'text'[:limit]


def prepare_output(parent: Path) -> int:
    parent.mkdir(parents=True, exist_ok=True)
    prefix_budget = min(32, MAX_OUTPUT_PATH - path_units(parent) - 34)
    if prefix_budget < 1:
        raise ValueError('Путь папки результата слишком длинный. Выбери более короткий путь, например D:\\Kotoba.')
    try:
        with tempfile.NamedTemporaryFile(dir=parent, prefix='.kotoba-check-', suffix='.tmp') as probe:
            probe.write(b'Kotoba'); probe.flush(); os.fsync(probe.fileno())
    except OSError as error:
        raise RuntimeError(f'Не удалось записать файл в папку {parent}: {error}') from error
    return prefix_budget


def new_result_folder(parent: Path, stem: str, budget: int) -> Path:
    for _ in range(10):
        folder = parent / f'{safe_stem(stem, budget)}_{uuid.uuid4().hex[:8]}'
        try:
            folder.mkdir()
            return folder
        except FileExistsError: continue
    raise RuntimeError('Не удалось создать уникальную папку результата.')


def verify_exports(paths):
    if not paths: raise RuntimeError('Список сохранённых файлов пуст.')
    sizes = {}
    for path in paths:
        try:
            text = path.read_text('utf-8-sig')
            if not text.strip(): raise ValueError('файл пуст')
            if path.suffix == '.srt' and not re.search(r'\d{2,}:\d{2}:\d{2},\d{3} --> ', text):
                raise ValueError('в SRT отсутствуют таймкоды')
            sizes[path] = path.stat().st_size
        except (OSError, ValueError) as error:
            raise RuntimeError(f'Не удалось проверить сохранённый файл {path}: {error}') from error
    return sizes


def export(segments: list[Segment], folder: Path, stem: str, language: str, log=None) -> tuple[Path, Path]:
    """Write and read back each result before reporting successful export."""
    paths = []
    budget = min(64, MAX_OUTPUT_PATH - path_units(folder) - 8)
    if budget < 8: raise ValueError('Путь результата слишком длинный. Выбери более короткую папку.')
    stem = safe_stem(stem, budget)
    texts = ('\n'.join(s.text for s in segments) + '\n',
             '\n\n'.join(f'{i}\n{timestamp(s.start)} --> {timestamp(max(s.end, s.start + .001))}\n{s.text}'
                         for i, s in enumerate(segments, 1)) + '\n')
    for ext, content in zip(('txt', 'srt'), texts):
        path = folder / f'{stem}.{language}.{ext}'
        temp = None
        try:
            fd, temp = tempfile.mkstemp(dir=folder, suffix='.tmp')
            with os.fdopen(fd, 'w', encoding='utf-8-sig', newline='\n') as f:
                f.write(content); f.flush(); os.fsync(f.fileno())
            os.replace(temp, path)
            if path.read_text('utf-8-sig') != content:
                raise RuntimeError('содержимое после записи не совпало с результатом')
            size = verify_exports([path])[path]
            if log: log(f'Файл проверен: {path} ({size} байт)')
        except Exception as error:
            raise RuntimeError(f'Ошибка сохранения {path}: {error}') from error
        finally:
            if temp:
                with suppress(OSError): Path(temp).unlink(missing_ok=True)
        paths.append(path)
    return tuple(paths)


def cuda_paths():
    """Make pip-installed CUDA runtime libraries visible to CTranslate2 on Windows."""
    if os.name != 'nt': return
    import site
    handles = []
    bases = site.getsitepackages()
    if getattr(sys, 'frozen', False): bases.append(sys._MEIPASS)
    for base in bases:
        for folder in (Path(base) / 'nvidia').glob('*/bin'):
            os.environ['PATH'] = str(folder) + os.pathsep + os.environ.get('PATH', '')
            handles.append(os.add_dll_directory(str(folder)))
    return handles

_DLL_HANDLES = cuda_paths()


def recognize(job, cancel, log):
    from faster_whisper import WhisperModel
    import ctranslate2
    device = job.device
    if device == 'auto': device = 'cuda' if ctranslate2.get_cuda_device_count() else 'cpu'
    compute = 'int8_float16' if device == 'cuda' else 'int8'
    log(f'Загрузка Whisper {job.model} • {device.upper()} • {compute}. Первый запуск скачивает модель.')
    check_cancel(cancel)
    model = WhisperModel(job.model, device=device, compute_type=compute,
                         download_root=str(job.models_dir / 'whisper'),
                         local_files_only=job.offline_only, cpu_threads=4)
    try:
        check_cancel(cancel)
        chunks, info = model.transcribe(str(job.source), language=job.language,
                                        task='transcribe', vad_filter=True, beam_size=5,
                                        condition_on_previous_text=False)
        result = []
        for chunk in chunks:
            check_cancel(cancel)
            text = chunk.text.strip()
            if text: result.append(Segment(chunk.start, chunk.end, text))
            log(f'Распознано {chunk.end:.0f} / {info.duration:.0f} сек.')
        check_cancel(cancel)
        return result
    finally:
        del model
        gc.collect()


class OnlineTranslator:
    def __init__(self, job):
        import requests
        self.job = job
        self.session = requests.Session()
    def close(self): self.session.close()
    def translate(self, text):
        j = self.job
        if j.translator == 'google':
            response = self.session.post('https://translation.googleapis.com/language/translate/v2',
                params={'key': j.api_key}, json={'q': text, 'source': j.language, 'target': j.target,
                                                'format': 'text'}, timeout=(15, 90))
        else:
            host = 'api-free.deepl.com' if j.deepl_free else 'api.deepl.com'
            response = self.session.post(f'https://{host}/v2/translate',
                headers={'Authorization': f'DeepL-Auth-Key {j.api_key}'},
                json={'text': [text], 'source_lang': j.language.upper(),
                      'target_lang': 'EN-US' if j.target == 'en' else j.target.upper()}, timeout=(15, 90))
        if response.status_code != 200:
            raise RuntimeError(f'{j.translator}: HTTP {response.status_code}. Проверь ключ, тариф, квоту и сеть.')
        data = response.json()
        if j.translator == 'google': return html.unescape(data['data']['translations'][0]['translatedText'])
        return data['translations'][0]['text']


class OfflineTranslator:
    def __init__(self, job, log):
        import torch
        from transformers import AutoTokenizer, AutoModelForSeq2SeqLM
        self.torch = torch
        self.target = job.target
        name = 'facebook/nllb-200-distilled-600M'
        opts = {'cache_dir': str(job.models_dir / 'translation'), 'local_files_only': job.offline_only}
        log('Загрузка NLLB-200 600M на CPU. Whisper уже освобождён из памяти.')
        self.tokenizer = AutoTokenizer.from_pretrained(name, src_lang=NLLB[job.language], **opts)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(name, use_safetensors=False, **opts).eval()
        torch.set_num_threads(4)
    def close(self):
        del self.model
        gc.collect()
    def translate(self, text):
        # Split unusually long subtitle segments without silently truncating them.
        parts = [text] if len(text) <= 250 else re.split(r'(?<=[.!?。！？])\s*', text)
        pieces = []
        for part in parts:
            if not part: continue
            pending = part
            while pending:
                cut = min(len(pending), 250)
                while cut > 1 and len(self.tokenizer(pending[:cut]).input_ids) > 350:
                    cut //= 2
                pieces.append(pending[:cut])
                pending = pending[cut:]
        out = []
        for piece in pieces:
            inputs = self.tokenizer(piece, return_tensors='pt')
            with self.torch.inference_mode():
                tokens = self.model.generate(**inputs, forced_bos_token_id=self.tokenizer.convert_tokens_to_ids(NLLB[self.target]),
                                             max_new_tokens=512, num_beams=4,
                                             no_repeat_ngram_size=3, repetition_penalty=1.1, early_stopping=True)
            out.append(self.tokenizer.batch_decode(tokens, skip_special_tokens=True)[0])
        return ' '.join(out)


def run_job(job: Job, cancel: threading.Event, log: Callable[[str], None],
            recognizer=recognize, translator_factory=None):
    if not job.source.is_file(): raise ValueError('Выбери существующий видео- или аудиофайл.')
    if job.language not in NLLB or job.target not in NLLB: raise ValueError('Неизвестный язык')
    if job.translator not in ('none', 'offline', 'google', 'deepl'): raise ValueError('Неизвестный переводчик')
    if job.language != job.target and job.translator in ('google', 'deepl') and not job.api_key.strip():
        raise ValueError('Для онлайн-перевода нужен API-ключ выбранного сервиса.')
    job = replace(job, source=job.source.expanduser().resolve(), output=job.output.expanduser().resolve())
    budget = prepare_output(job.output)
    log(f'Папка сохранения проверена: {job.output}')
    log('Этап 1/2: распознавание. Файлы появятся после получения текста.')
    original = recognizer(job, cancel, log)
    check_cancel(cancel)
    if not original: raise ValueError('Речь не найдена. Проверь аудиодорожку и исходный язык.')
    folder = new_result_folder(job.output, job.source.stem, budget)
    try:
        paths = list(export(original, folder, job.source.stem, job.language, log))
    except Exception:
        with suppress(OSError): folder.rmdir()
        raise
    log(f'Исходная расшифровка сохранена: {folder}')
    if job.language != job.target and job.translator != 'none':
        translator = None
        try:
            check_cancel(cancel)
            factory = translator_factory or (lambda j: OfflineTranslator(j, log) if j.translator == 'offline' else OnlineTranslator(j))
            log('Этап 2/2: перевод. Исходные TXT/SRT уже сохранены.')
            translator = factory(job)
            translated = []
            for i, segment in enumerate(original, 1):
                check_cancel(cancel)
                text = translator.translate(segment.text).strip()
                if not text: raise RuntimeError('Переводчик вернул пустой текст')
                translated.append(replace(segment, text=text))
                log(f'Перевод: {i} / {len(original)}')
            check_cancel(cancel)
            paths.extend(export(translated, folder, job.source.stem, job.target, log))
        except Exception:
            log(f'Перевод не завершён. Исходные TXT/SRT доступны в {folder}')
            raise
        finally:
            if translator: translator.close()
    verify_exports(paths)
    log(f'Готово: проверено {len(paths)} файлов. ' + '\n'.join(str(p) for p in paths))
    return paths
