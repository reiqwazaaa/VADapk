@echo off
setlocal
cd /d "%~dp0"
if not exist .venv\Scripts\python.exe (
 echo Run install.bat first.
 pause
 exit /b 1
)
.venv\Scripts\python.exe -m pip install "nvidia-cublas-cu12>=12.4,<13" "nvidia-cudnn-cu12>=9.1,<10" "nvidia-cuda-runtime-cu12>=12.4,<13"
if errorlevel 1 (
 echo GPU installation failed.
 pause
 exit /b 1
)
echo Done. Update your NVIDIA driver, then select auto or cuda in Kotoba.
pause
