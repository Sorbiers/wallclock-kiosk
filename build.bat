@echo off
REM Build the Wall Clock app -> dist\wallclock.apk
REM (web\ and android\ are packed by android\build.py; needs a JDK and the Android SDK.)
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

python android\build.py
if errorlevel 1 (
    echo.
    echo BUILD FAILED
)

REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
