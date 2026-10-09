@echo off
REM Install dist\wallclock.apk on the tablet and start it. Settings on the tablet are kept.
REM By cable when the tablet is plugged in (USB debugging on), else over Wi-Fi
REM (run wifi-on.bat once, with the cable in, to allow that).
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python tools\deploy.py

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
