@echo off
REM Install dist\wallclock.apk on the tablet over USB and start it.
REM Settings on the tablet are kept. Needs USB debugging on and the tablet plugged in.
cd /d "%~dp0"

set "SDK=%ANDROID_SDK_ROOT%"
if "%SDK%"=="" set "SDK=F:\AndroidSdk"
set "ADB=%SDK%\platform-tools\adb.exe"

if not exist dist\wallclock.apk (
    echo dist\wallclock.apk not found - run build.bat first.
    goto done
)

"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
    echo Tablet not found. Plug it in, and accept "Allow USB debugging" on its screen.
    goto done
)

"%ADB%" install -r dist\wallclock.apk
if errorlevel 1 goto done
"%ADB%" shell am start -n home.wallclock/.MainActivity

:done
REM Keep the window open when started by double-click.
echo %cmdcmdline% | "%SystemRoot%\System32\find.exe" /i "/c" >nul && pause
