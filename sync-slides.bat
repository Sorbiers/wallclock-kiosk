@echo off
REM Copy the slideshow pictures to the tablet (tablet plugged in, USB debugging on).
REM Put pictures in the slides\ folder next to this file, then run this.
REM New pictures are added, deleted ones are removed from the tablet too.
REM Usage: sync-slides.bat [folder]      (default: slides\)
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python tools\sync_slides.py %*

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
