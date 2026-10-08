@echo off
REM Show the page on this PC, for debugging and improving it without the tablet.
REM dev\server.py stands in for the app's native side (camera login comes from
REM dev\cameras.env). It opens the browser itself. Edit files in web\ and
REM refresh the page. Ctrl+C stops it.
REM Usage: serve.bat [port]      (default 8080)
cd /d "%~dp0"
if exist "%~dp0local.bat" call "%~dp0local.bat"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"

python dev\server.py %PORT% --open

REM Reached only if the server could not start (e.g. the port is in use).
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
