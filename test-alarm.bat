@echo off
REM Pretend a camera alarm on the tablet (pictures and live view are real).
REM Usage: test-alarm.bat [camera] [tablet address]     camera: 1 or 2
REM Started without a camera number, it asks for one.
cd /d "%~dp0"

set "CAM=%~1"
if "%CAM%"=="" set /p "CAM=Camera (1 or 2) [1]: "
if "%CAM%"=="" set "CAM=1"

python tools\test_alarm.py %CAM% %2

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
