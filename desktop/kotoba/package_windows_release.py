from pathlib import Path
from zipfile import ZipFile,ZIP_DEFLATED
import hashlib,json,subprocess,shutil,sys
root=Path.cwd();app=root/'dist'/'Kotoba';output=root/'release-assets';output.mkdir(exist_ok=True)
report=json.loads((root/'frozen-self-test.json').read_text('utf-8'));assert all(v['ok'] for v in report.values())
shutil.copy2(root/'frozen-self-test.json',app/'self-test.json');shutil.copy2(root/'THIRD_PARTY.md',app/'THIRD_PARTY.md')
help_text='''Kotoba 0.2.0 — живая расшифровка, Windows x64

Новая установка: распакуй всю папку Kotoba-Windows и запусти Kotoba.exe.
Обновление с 0.1.2: закрой приложение, распакуй Kotoba-update-0.2.0.zip
в прежнюю папку с заменой файлов. Папка _internal из обновления должна
объединиться с прежней, а не заменять её целиком. Модели и настройки остаются.

В главном окне выбери исходный/целевой языки, Whisper, переводчик и папку
сохранения. Нажми «Живой режим». Обнови список устройств; выбери системный
звук (WASAPI loopback) или микрофон и конкретное устройство.
Нажми «Начать слушать»: захват начнётся после подготовки моделей.

Субтитры можно показать поверх окон, изменить шрифт/прозрачность и перетащить.
Серый текст — предварительный. Оригинал подтверждается после паузы/лимита
фразы, перевод поступает отдельно с задержкой. Профиль «Быстро» ограничивает
фразы 6 секундами, «Баланс» — 9, «Контекст» — 14. Для RTX 3070 начни с
large-v3-turbo/CUDA. Если не успевает — small и «Быстро».
Если речь тихая — уменьши порог; если пишется шум — увеличь его.

«Остановить» прекращает захват и сохраняет полученные фразы. Журнал и папку
результата можно открыть из панели. В session.json отмечены пропуски звука и
перевода. При сбое черновик остаётся в preview.txt. Захват и перевод имеют
ограниченные очереди: пропуски при отставании явно отмечаются.
Онлайн-перевод получает только подтверждённый текст; аудио наружу не отправляется.
API-ключ вводится в главном окне, в файлах настроек/запросов он не сохраняется.
NLLB работает локально на CPU; лицензия модели CC-BY-NC 4.0.

Аудиодрайверы и реальное время на RTX в сборочном окружении не проверялись.
Проверены Windows EXE, импорт аудиобиблиотеки, интерфейс/оверлей и обработка
аудиопотока из файла тем же движком. Задержку нужно измерить на твоём ПК.
Для игр с эксклюзивным fullscreen используй оконный/безрамочный режим.
'''
(app/'НАЧАТЬ ЗДЕСЬ.txt').write_text(help_text,'utf-8-sig')
sha=hashlib.sha256((app/'Kotoba.exe').read_bytes()).hexdigest()
(app/'BUILD-INFO.md').write_text(f'# Kotoba 0.2.0 — Windows x64\n\nPython {sys.version.split()[0]}, PyInstaller 6.16.0.\n\nПрошли 39 тестов: процессы ASR, аварии/сохранение, live-фразы, согласование предварительного текста, очереди, преобразование PCM 48→16 кГц на подставленном устройстве, остановка и контроль сохранённых файлов. Проверены обычный EXE, live-панель/оверлей, PyAudioWPatch import, реальный Whisper tiny на последовательных порциях тестового аудиофайла. Кодек и интерфейс устройств проверены без физического WASAPI-источника.\n\nОбновление проверено с библиотеками опубликованной 0.1.2 после добавления PyAudioWPatch. Модели скачиваются отдельно.\n\nНа раннере нет RTX и реальных аудиоустройств: GPU-скорость, реальный loopback/микрофон и end-to-end задержка не измерялись. Оценки задержки не являются гарантией.\n\nSHA256 Kotoba.exe: `{sha}`\n','utf-8')
with ZipFile(output/'Kotoba-Windows.zip','w',ZIP_DEFLATED,compresslevel=6) as z:
 for p in sorted(app.rglob('*')):
  if p.is_file() and '__pycache__' not in p.parts:z.write(p,Path('Kotoba-Windows')/p.relative_to(app))
with ZipFile(output/'Kotoba-update-0.2.0.zip','w',ZIP_DEFLATED) as z:
 z.write(app/'Kotoba.exe','Kotoba.exe');z.writestr('НАЧАТЬ ЗДЕСЬ.txt',help_text.encode('utf-8-sig'))
 for folder in (app/'_internal').iterdir():
  if 'pyaudiowpatch' in folder.name.lower():
   for p in sorted(folder.rglob('*')):
    if p.is_file():z.write(p,p.relative_to(app))
tracked=subprocess.check_output(['git','ls-files','--','.'],text=True).splitlines()
with ZipFile(output/'Kotoba.zip','w',ZIP_DEFLATED) as z:
 for name in tracked:
  if (root/name).is_file():z.write(root/name,Path('Kotoba')/name)
checks=[]
for name in ['Kotoba-Windows.zip','Kotoba-update-0.2.0.zip','Kotoba.zip']:
 with ZipFile(output/name) as z:assert z.testzip() is None
 h=hashlib.sha256()
 with (output/name).open('rb') as f:
  for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
 checks.append(h.hexdigest()+'  '+name)
(output/'SHA256SUMS.txt').write_text('\n'.join(checks)+'\n','utf-8');print('\n'.join(checks))
