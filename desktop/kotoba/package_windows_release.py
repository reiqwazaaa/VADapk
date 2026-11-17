from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import json,hashlib,subprocess,shutil,sys
root=Path.cwd();app=root/'dist'/'Kotoba';output=root/'release-assets';output.mkdir(exist_ok=True)
report=json.loads((root/'frozen-self-test.json').read_text('utf-8'))
assert all(v['ok'] for v in report.values())
shutil.copy2(root/'frozen-self-test.json',app/'self-test.json')
shutil.copy2(root/'THIRD_PARTY.md',app/'THIRD_PARTY.md')
(app/'НАЧАТЬ ЗДЕСЬ.txt').write_text('Kotoba — Windows x64\n\nРаспакуй всю папку и запусти Kotoba.exe. Папка _internal должна оставаться рядом.\nPython и CUDA-библиотеки включены. Нужен актуальный драйвер NVIDIA.\nДля RTX 3070 начни с large-v3-turbo и устройства auto. Модели загружаются при первой задаче.\nВыбери видео/аудио, языки и папку результата. Нажми «Распознать и сохранить».\nЛокальный NLLB использует CPU и лицензию CC-BY-NC 4.0; Google/DeepL требуют API-ключа.\nПодробнее о проверке: BUILD-INFO.md.\n','utf-8-sig')
sha=hashlib.sha256((app/'Kotoba.exe').read_bytes()).hexdigest()
(app/'BUILD-INFO.md').write_text(f'# Kotoba 0.1.1 — Windows x64\n\nСобрано на GitHub Actions windows-latest, Python {sys.version.split()[0]}, PyInstaller 6.16.0.\n\nПроверено на Windows: 16 тестов приложения; окно EXE; загрузка движков; генерация малой конфигурацией M2M100 (архитектура NLLB); реальное распознавание записи JFK через Whisper tiny/int8 с VAD и сохранением TXT/SRT.\n\nGPU на раннере отсутствует: выполнение CUDA и скорость RTX 3070 не проверялись. CUDA 12.9.79, cuBLAS 12.9.1.4, cuDNN 9.10.2.21 и Visual C++ runtime включены.\n\nПолные модели Whisper/NLLB скачиваются отдельно при первой задаче. Онлайн Google/DeepL проверены контрактными тестами без платных запросов.\n\nSHA256 Kotoba.exe: `{sha}`\n','utf-8')
with ZipFile(output/'Kotoba-Windows.zip','w',ZIP_DEFLATED,compresslevel=6) as z:
 for p in sorted(app.rglob('*')):
  if p.is_file() and '__pycache__' not in p.parts:z.write(p,Path('Kotoba-Windows')/p.relative_to(app))
tracked=subprocess.check_output(['git','ls-files','--','.'],text=True).splitlines()
with ZipFile(output/'Kotoba.zip','w',ZIP_DEFLATED) as z:
 for name in tracked:
  p=root/name
  if p.is_file():z.write(p,Path('Kotoba')/name)
hotfix_text = 'Kotoba 0.1.1 — исправление сохранения\n\nЗакрой Kotoba 0.1.0. Распакуй Kotoba.exe из этого ZIP поверх старого Kotoba.exe в той же папке. Папку _internal оставь от версии 0.1.0.\n\nЭтот комплект проверен на Windows с библиотеками опубликованной версии 0.1.0: окно, движки, генерация перевода и распознавание с экспортом при длинном имени файла.\n\nНовая версия ограничивает длину путей Windows, читает сохранённые файлы перед сообщением об успехе и ведёт журнал задачи. Кнопка «Журнал» открывает подробности сбоя.\n'
with ZipFile(output/'Kotoba-hotfix-0.1.1.zip','w',ZIP_DEFLATED) as z:
 z.write(app/'Kotoba.exe','Kotoba.exe')
 z.writestr('НАЧАТЬ ЗДЕСЬ.txt',hotfix_text.encode('utf-8-sig'))
checks=[]
for name in ['Kotoba-Windows.zip','Kotoba.zip','Kotoba-hotfix-0.1.1.zip']:
 with ZipFile(output/name) as z:assert z.testzip() is None
 h=hashlib.sha256()
 with (output/name).open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 checks.append(h.hexdigest()+'  '+name)
(output/'SHA256SUMS.txt').write_text('\n'.join(checks)+'\n','utf-8')
print('\n'.join(checks),flush=True)
