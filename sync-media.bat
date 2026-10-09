@echo off
REM Copy what the alarm clock can play to the tablet: music, videos and pictures.
REM Put the files in the media\ folder next to this file, then run this.
REM New files are added, deleted ones are removed from the tablet too.
REM By cable when the tablet is plugged in (USB debugging on), else over Wi-Fi
REM (see wifi-on.bat).
REM Usage: sync-media.bat [folder]      (default: media\)
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python tools\sync_media.py %*

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
