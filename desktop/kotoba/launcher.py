"""Desktop entry point with explicit packaging diagnostics."""
import json
import sys
from pathlib import Path
from kotoba.app import App


def main():
    if len(sys.argv) > 2 and sys.argv[1] == '--self-test':
        import importlib
        result = {}
        for name in ('faster_whisper', 'ctranslate2', 'torch', 'transformers', 'sentencepiece', 'requests', 'av', 'onnxruntime',
                     'transformers.models.m2m_100.modeling_m2m_100', 'transformers.models.nllb.tokenization_nllb_fast'):
            try:
                module = importlib.import_module(name)
                result[name] = {'ok': True, 'version': getattr(module, '__version__', 'n/a')}
            except Exception as error:
                import traceback
                result[name] = {'ok': False, 'error': str(error), 'traceback': traceback.format_exc()}
        try:
            import torch
            from transformers import M2M100Config, M2M100ForConditionalGeneration
            config = M2M100Config(vocab_size=128, d_model=32, encoder_layers=1, decoder_layers=1,
                                  encoder_attention_heads=4, decoder_attention_heads=4,
                                  encoder_ffn_dim=64, decoder_ffn_dim=64, max_position_embeddings=64)
            model = M2M100ForConditionalGeneration(config).eval()
            with torch.inference_mode():
                generated = model.generate(input_ids=torch.tensor([[5, 6, 2]]),
                                           forced_bos_token_id=10, max_new_tokens=4)
            result['translation_generation'] = {'ok': generated.shape[-1] >= 2}
        except Exception as error:
            import traceback
            result['translation_generation'] = {'ok': False, 'error': str(error), 'traceback': traceback.format_exc()}
        Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        return 0 if all(item['ok'] for item in result.values()) else 1
    if len(sys.argv) > 3 and sys.argv[1] == '--smoke-asr':
        import threading
        from kotoba.core import Job, run_job
        folder = Path(sys.argv[3])
        folder.mkdir(parents=True, exist_ok=True)
        logs = []
        try:
            job = Job(Path(sys.argv[2]), folder, language='en', target='en', model='tiny', device='cpu',
                      translator='none', models_dir=folder / 'models')
            files = run_job(job, threading.Event(), logs.append)
            result = {'ok': True, 'files': [str(p) for p in files], 'log': logs}
        except Exception:
            import traceback
            result = {'ok': False, 'traceback': traceback.format_exc(), 'log': logs}
        (folder / 'report.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        return 0 if result['ok'] else 1
    if len(sys.argv) > 2 and sys.argv[1] == '--smoke-ui':
        app = App()
        app.update()
        result = {'title': app.title(), 'width': app.winfo_width(), 'height': app.winfo_height(),
                  'controls': len(app.controls)}
        app.destroy()
        Path(sys.argv[2]).write_text(json.dumps(result, ensure_ascii=False, indent=2), 'utf-8')
        return 0
    App().mainloop()
    return 0


if __name__ == '__main__':
    import multiprocessing
    multiprocessing.freeze_support()
    raise SystemExit(main())
