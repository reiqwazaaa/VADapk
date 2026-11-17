@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe exit /b 1
.venv\Scripts\python.exe -m pip install pyinstaller==6.16.0
if errorlevel 1 exit /b 1
.venv\Scripts\python.exe -m PyInstaller --noconfirm --clean Kotoba.spec
if errorlevel 1 goto fail
.venv\Scripts\python.exe bundle_gpu.py dist\Kotoba\_internal
if errorlevel 1 goto fail
echo Built: dist\Kotoba\Kotoba.exe. Keep the entire Kotoba folder together.
pause
exit /b 0
:fail
echo Build failed. See the error above.
pause
exit /b 1
