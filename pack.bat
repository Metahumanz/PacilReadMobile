@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "MODE=%~1"
if "%MODE%"=="" set "MODE=debug"

if /I "%MODE%"=="debug" (
  set "TASK=assembleDebug"
  set "OUTPUT=app\build\outputs\apk\debug\app-debug.apk"
) else if /I "%MODE%"=="release" (
  set "TASK=assembleRelease"
  set "OUTPUT=app\build\outputs\apk\release\"
) else if /I "%MODE%"=="bundle" (
  set "TASK=bundleRelease"
  set "OUTPUT=app\build\outputs\bundle\release\"
) else if /I "%MODE%"=="install" (
  set "TASK=installDebug"
  set "OUTPUT=installed-to-device"
) else if /I "%MODE%"=="clean" (
  set "TASK=clean"
  set "OUTPUT=build artifacts cleared"
) else (
  echo Usage:
  echo   pack.bat debug    ^(default, builds debug APK^)
  echo   pack.bat release  ^(builds release APK, usually needs signing^)
  echo   pack.bat bundle   ^(builds release AAB for app store upload^)
  echo   pack.bat install  ^(builds and installs debug APK to connected device^)
  echo   pack.bat clean    ^(clears build outputs^)
  exit /b 1
)

call :ensure_sdk
if errorlevel 1 exit /b %errorlevel%

echo.
echo [PacilRead] Running Gradle task: %TASK%
call .\gradlew.bat %TASK% --no-daemon --console plain
if errorlevel 1 exit /b %errorlevel%

echo.
echo [PacilRead] Done: %TASK%
echo [PacilRead] Output: %OUTPUT%
exit /b 0

:ensure_sdk
if exist "local.properties" (
  echo [PacilRead] Using existing local.properties
  exit /b 0
)

set "SDK_DIR="
if defined ANDROID_HOME set "SDK_DIR=%ANDROID_HOME%"
if not defined SDK_DIR if defined ANDROID_SDK_ROOT set "SDK_DIR=%ANDROID_SDK_ROOT%"
if not defined SDK_DIR if exist "C:\Android\Sdk" set "SDK_DIR=C:\Android\Sdk"
if not defined SDK_DIR if exist "C:\Android\SDK" set "SDK_DIR=C:\Android\SDK"
if not defined SDK_DIR if exist "%LOCALAPPDATA%\Android\Sdk" set "SDK_DIR=%LOCALAPPDATA%\Android\Sdk"

if not defined SDK_DIR (
  echo [PacilRead] Android SDK not found.
  echo [PacilRead] Please set ANDROID_HOME or create local.properties manually.
  exit /b 1
)

set "ESCAPED_SDK=!SDK_DIR:\=\\!"
(
  echo sdk.dir=!ESCAPED_SDK!
) > local.properties

echo [PacilRead] Detected Android SDK: !SDK_DIR!
echo [PacilRead] Generated local.properties automatically.
exit /b 0
