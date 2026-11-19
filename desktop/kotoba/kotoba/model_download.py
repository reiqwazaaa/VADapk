"""Resumable NLLB setup and short, actionable download errors."""
from __future__ import annotations
import os
from pathlib import Path
import socket
import sys
import time

NLLB_REPO = 'facebook/nllb-200-distilled-600M'
NLLB_FILES = ['config.json', 'generation_config.json', 'pytorch_model.bin',
              'sentencepiece.bpe.model', 'tokenizer.json', 'tokenizer_config.json',
              'special_tokens_map.json']


class ModelDownloadError(RuntimeError):
    pass


def is_network_error(error):
    import requests
    seen = set()
    while error is not None and id(error) not in seen:
        seen.add(id(error))
        if isinstance(error, (requests.ConnectionError, requests.Timeout,
                              ConnectionError, TimeoutError, socket.gaierror)):
            return True
        if isinstance(error, requests.HTTPError):
            status = getattr(error.response, 'status_code', 0)
            if status == 429 or status >= 500:
                return True
        error = error.__cause__ or error.__context__
    return False


def complete_snapshot(folder):
    folder = Path(folder)
    required = ['config.json', 'pytorch_model.bin', 'tokenizer_config.json']
    return all((folder / name).is_file() and (folder / name).stat().st_size > 0 for name in required) and any(
        (folder / name).is_file() and (folder / name).stat().st_size > 0
        for name in ('tokenizer.json', 'sentencepiece.bpe.model'))


def prepare_nllb(job, log):
    from huggingface_hub import constants, snapshot_download
    from huggingface_hub.errors import LocalEntryNotFoundError
    if sys.stderr is None:
        # Windowed PyInstaller apps have no console stream for Hub's progress bar.
        from huggingface_hub.utils import disable_progress_bars
        disable_progress_bars()
    cache = str(job.models_dir / 'translation')
    try:
        cached = snapshot_download(NLLB_REPO, cache_dir=cache, local_files_only=True)
    except LocalEntryNotFoundError:
        cached = None
    if cached and complete_snapshot(cached):
        log('NLLB уже скачана. Загружаю с диска без обращения к интернету.')
        return Path(cached)
    if job.offline_only:
        raise ModelDownloadError('NLLB ещё не скачана полностью. Выключи строгий офлайн-режим и повтори запуск при доступном интернете. Папку моделей удалять не нужно.')
    # Whisper may have imported the Hub earlier in this process, so update both.
    constants.HF_HUB_DOWNLOAD_TIMEOUT = max(constants.HF_HUB_DOWNLOAD_TIMEOUT, 120)
    os.environ['HF_HUB_DOWNLOAD_TIMEOUT'] = str(constants.HF_HUB_DOWNLOAD_TIMEOUT)
    log('Скачивание офлайн-переводчика NLLB, около 2,5 ГБ. Скачанная часть сохраняется для докачки.')
    for attempt in range(3):
        try:
            folder = snapshot_download(NLLB_REPO, cache_dir=cache,
                allow_patterns=NLLB_FILES, max_workers=2, etag_timeout=20)
            if not complete_snapshot(folder):
                raise ModelDownloadError('Модель NLLB скачана не полностью. Повтори запуск; скачанные файлы сохраняются.')
            log('NLLB скачана. Следующие запуски не требуют интернета.')
            return Path(folder)
        except Exception as error:
            if not is_network_error(error):
                raise
            if attempt == 2:
                raise ModelDownloadError('Не удалось скачать NLLB: нет ответа от Hugging Face или не работает DNS. Проверь соединение и доступ к huggingface.co, затем повтори запуск. Скачанная часть модели сохранена; папку моделей удалять не нужно. Можно выбрать Google/DeepL с API-ключом.') from error
            log(f'Сеть недоступна. Повторная попытка {attempt + 2}/3 с докачкой…')
            time.sleep(2 * (attempt + 1))
