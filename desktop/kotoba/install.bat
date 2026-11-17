@echo off
setlocal
cd /d "%~dp0"
py -3.11 --version >nul 2>&1
if errorlevel 1 (
 echo Install Python 3.11 x64 from https://www.python.org/downloads/windows/
 echo Enable the Python launcher during setup, then run install.bat again.
 pause
 exit /b 1
)
if not exist .venv py -3.11 -m venv .venv
.venv\Scripts\python.exe -m pip install --upgrade pip
if errorlevel 1 goto fail
.venv\Scripts\python.exe -m pip install torch==2.6.0 --index-url https://download.pytorch.org/whl/cpu
if errorlevel 1 goto fail
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
echo Done. Run start.bat. For RTX acceleration also run install-gpu.bat.
pause
exit /b 0
:fail
echo Installation failed. Check the error above and your network connection.
pause
exit /b 1
