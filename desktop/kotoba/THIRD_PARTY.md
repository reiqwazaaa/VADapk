# Используемые компоненты

Устанавливаются отдельно через pip; веса моделей загружаются с Hugging Face.

- [faster-whisper](https://github.com/SYSTRAN/faster-whisper), MIT.
- [CTranslate2](https://github.com/OpenNMT/CTranslate2), MIT.
- [Whisper](https://github.com/openai/whisper), MIT; конвертированные модели [Systran](https://huggingface.co/Systran), turbo [mobiuslabsgmbh](https://huggingface.co/mobiuslabsgmbh/faster-whisper-large-v3-turbo). Проверяй карточку конкретной модели перед распространением.
- [Transformers](https://github.com/huggingface/transformers), Apache 2.0.
- [PyTorch](https://github.com/pytorch/pytorch), BSD-style.
- [NLLB-200 distilled 600M](https://huggingface.co/facebook/nllb-200-distilled-600M), CC-BY-NC 4.0. Ограничение на коммерческое использование относится к модели.
- [PyAV](https://github.com/PyAV-Org/PyAV), BSD-3-Clause; включённые сборки FFmpeg имеют собственные лицензии.
- [SentencePiece](https://github.com/google/sentencepiece), Apache 2.0.
- [Requests](https://requests.readthedocs.io/), Apache 2.0.
- CUDA/cuDNN: собственные условия NVIDIA. GPU-компоненты ставятся отдельно опциональным скриптом.
- Google Cloud Translation / DeepL: условия соответствующих сервисов, отдельный API-ключ.

При распространении собранного EXE сохрани лицензии зависимостей и проверь условия NVIDIA, FFmpeg и моделей. В ZIP этой версии нет весов и бинарных библиотек.

- Microsoft Visual C++ 2015–2022 Redistributable: необходимые DLL x64 включены в переносимую сборку. [Условия и сведения](https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist).
