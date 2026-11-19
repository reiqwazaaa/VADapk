# Windows x64 portable application. Build with Python 3.11 and PyInstaller 6.16.
from PyInstaller.utils.hooks import collect_all, collect_data_files, collect_dynamic_libs, collect_submodules, copy_metadata, get_package_paths

datas, binaries, hiddenimports = [], [], []
for package in ('faster_whisper', 'ctranslate2', 'tokenizers', 'sentencepiece', 'av', 'onnxruntime', 'pyaudiowpatch'):
    package_data, package_binaries, package_imports = collect_all(package, include_py_files=False)
    datas += package_data
    binaries += package_binaries
    hiddenimports += package_imports

datas += collect_data_files('torch', include_py_files=False,
    excludes=['include/**', 'test/**', 'testing/_internal/**', '**/*.lib', '**/*.h', '**/*.hpp', '**/*.pdb'])
binaries += collect_dynamic_libs('torch')
# Torch checkpoint imports this runtime helper despite its testing namespace.
from pathlib import Path
_, torch_package = get_package_paths('torch')
for helper in ('__init__.py', 'logging_tensor.py'):
    datas.append((str(Path(torch_package) / 'testing' / '_internal' / helper), 'torch/testing/_internal'))
for helper in ('__init__.py', 'fake_pg.py'):
    datas.append((str(Path(torch_package) / 'testing' / '_internal' / 'distributed' / helper), 'torch/testing/_internal/distributed'))
datas += collect_data_files('transformers', include_py_files=False)
for package in ('transformers.models.auto', 'transformers.models.m2m_100',
                'transformers.models.nllb', 'transformers.generation', 'transformers.integrations'):
    hiddenimports += collect_submodules(package, on_error='warn once')
hiddenimports += ['torch', 'transformers', 'transformers.tokenization_utils_fast',
                  'transformers.tokenization_utils', 'transformers.modeling_utils']
for package in ('transformers', 'torch', 'numpy', 'tokenizers', 'huggingface-hub',
                'safetensors', 'tqdm', 'regex', 'requests', 'packaging', 'filelock',
                'pyyaml', 'faster-whisper', 'ctranslate2', 'onnxruntime'):
    datas += copy_metadata(package)

a = Analysis(['launcher.py'], pathex=[], binaries=binaries, datas=datas,
             hiddenimports=hiddenimports, hookspath=[], hooksconfig={}, runtime_hooks=[],
             excludes=['IPython', 'matplotlib', 'pytest', 'scipy', 'sklearn', 'pandas', 'torch.testing._internal'],
             noarchive=False)
a.datas = [item for item in a.datas if not item[0].lower().endswith(('.lib', '.h', '.hpp', '.pdb'))
           and not item[0].replace('\\', '/').startswith('torch/include/')]
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='Kotoba', debug=False,
          bootloader_ignore_signals=False, strip=False, upx=False, console=False)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name='Kotoba')
