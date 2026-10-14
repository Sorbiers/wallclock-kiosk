@echo off
REM Forget the tablet's master PIN (and the lock on its settings), for when it is forgotten.
REM Goes by USB cable (or Wi-Fi after wifi-on.bat); the other settings stay.
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python tools\reset_master.py

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
