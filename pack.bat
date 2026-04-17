@echo off
setlocal

set MODE=%~1
if "%MODE%"=="" set MODE=debug

if /I "%MODE%"=="debug" (
  set TASK=assembleDebug
  set OUTPUT=app\build\outputs\apk\debug\app-debug.apk
) else if /I "%MODE%"=="release" (
  set TASK=assembleRelease
  set OUTPUT=app\build\outputs\apk\release\
) else if /I "%MODE%"=="bundle" (
  set TASK=bundleRelease
  set OUTPUT=app\build\outputs\bundle\release\
) else if /I "%MODE%"=="install" (
  set TASK=installDebug
  set OUTPUT=installed-to-device
) else (
  echo Usage:
  echo   pack.bat debug    ^(default, builds debug APK^)
  echo   pack.bat release  ^(builds release APK, usually needs signing^)
  echo   pack.bat bundle   ^(builds release AAB for app store upload^)
  echo   pack.bat install  ^(builds and installs debug APK to connected device^)
  exit /b 1
)

call .\gradlew.bat %TASK% --no-daemon --console plain
if errorlevel 1 exit /b %errorlevel%

echo.
echo Done: %TASK%
echo Output: %OUTPUT%

endlocal
