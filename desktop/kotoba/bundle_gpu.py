"""Copy optional NVIDIA runtime wheels alongside the frozen application."""
import shutil
import site
import sys
from pathlib import Path

target = Path(sys.argv[1])
found = False
for base in map(Path, site.getsitepackages()):
    source = base / 'nvidia'
    if source.is_dir():
        shutil.copytree(source, target / 'nvidia', dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc', 'include'))
        for meta in base.glob('nvidia_*.dist-info'):
            shutil.copytree(meta, target / meta.name, dirs_exist_ok=True)
        found = True
print('NVIDIA runtime bundled.' if found else 'CPU build: optional NVIDIA packages were not installed.')
