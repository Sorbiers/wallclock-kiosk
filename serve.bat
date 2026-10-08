@echo off
REM Show the page on this PC, for debugging and improving it without the tablet.
REM dev\server.py stands in for the app's native side (camera login comes from
REM Butler's .env). It opens the browser itself. Edit files in web\ and refresh
REM the page. Ctrl+C stops it.
REM Usage: serve.bat [port]      (default 8080)
cd /d "%~dp0"

set "PORT=%~1"
if "%PORT%"=="" set "PORT=8080"

python dev\server.py %PORT% --open

REM Reached only if the server could not start (e.g. the port is in use).
echo %cmdcmdline% | find /i "/c" >nul && pause
