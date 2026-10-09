@echo off
REM Let deploy.bat and sync-slides.bat reach the tablet over Wi-Fi, without the cable.
REM Run it with the tablet plugged in. The tablet forgets this when it restarts:
REM plug it in and run this again then.
REM While it is on, the tablet's debug port is open on the home network, to the
REM computers the tablet has already allowed (this PC).
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python tools\tablet.py

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
