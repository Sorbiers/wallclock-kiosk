@echo off
REM Copy the slideshow pictures to the tablet (tablet plugged in, USB debugging on).
REM Put pictures in the slides\ folder next to this file, then run this.
REM New pictures are added, deleted ones are removed from the tablet too.
REM Usage: sync-slides.bat [folder]      (default: slides\)
cd /d "%~dp0"

python tools\sync_slides.py %*

REM Keep the window open when started by double-click.
echo %cmdcmdline% | find /i "/c" >nul && pause
