@echo off
REM This PC's own paths, picked up by the other .bat files. Copy to local.bat (git ignores it).
REM Where the Android SDK is, if not in %LOCALAPPDATA%\Android\Sdk:
set "ANDROID_SDK_ROOT=C:\Android\Sdk"
REM The env file with the camera login for serve.bat, if not dev\cameras.env:
set "WALLCLOCK_ENV=C:\path\to\cameras.env"
