@echo off
setlocal EnableExtensions EnableDelayedExpansion
goto :main

:ensure_sdk
if exist "local.properties" (
  call :load_sdk_from_local_properties
  if errorlevel 1 exit /b 1
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

:load_sdk_from_local_properties
set "SDK_DIR="
for /f "usebackq tokens=1,* delims==" %%A in ("local.properties") do (
  if /I "%%A"=="sdk.dir" set "SDK_DIR=%%B"
)
if not defined SDK_DIR (
  echo [PacilRead] local.properties exists but sdk.dir is missing.
  exit /b 1
)
set "SDK_DIR=!SDK_DIR:\:=:!"
set "SDK_DIR=!SDK_DIR:\\=\!"
exit /b 0

:preflight_install
set "ADB_EXE="
if defined SDK_DIR if exist "!SDK_DIR!\platform-tools\adb.exe" set "ADB_EXE=!SDK_DIR!\platform-tools\adb.exe"
if not defined ADB_EXE (
  echo [PacilRead] adb.exe not found under "!SDK_DIR!\platform-tools".
  echo [PacilRead] Please install Android SDK Platform-Tools first.
  exit /b 1
)

"!ADB_EXE!" start-server >nul 2>nul
set "ONLINE_DEVICE="
set "UNAUTHORIZED_DEVICE="
set "OFFLINE_DEVICE="

for /f "skip=1 tokens=1,2" %%A in ('"!ADB_EXE!" devices') do (
  if not "%%A"=="" if /I not "%%A"=="List" (
    if /I "%%B"=="device" set "ONLINE_DEVICE=%%A"
    if /I "%%B"=="unauthorized" set "UNAUTHORIZED_DEVICE=%%A"
    if /I "%%B"=="offline" set "OFFLINE_DEVICE=%%A"
  )
)

if defined ONLINE_DEVICE exit /b 0

echo.
if defined UNAUTHORIZED_DEVICE (
  echo [PacilRead] Device !UNAUTHORIZED_DEVICE! is connected but not authorized.
  echo [PacilRead] Please unlock your phone and tap "Allow USB debugging".
  echo [PacilRead] If the prompt does not appear:
  echo [PacilRead]   1. Replug the USB cable
  echo [PacilRead]   2. Toggle USB debugging off and on in Developer options
  echo [PacilRead]   3. Run "!ADB_EXE!" kill-server, reconnect, and accept the fingerprint
  echo [PacilRead] Then run: pack.bat install
  exit /b 1
)

if defined OFFLINE_DEVICE (
  echo [PacilRead] Device !OFFLINE_DEVICE! is offline.
  echo [PacilRead] Replug the cable or restart adb, then try pack.bat install again.
  exit /b 1
)

echo [PacilRead] No online Android device found.
echo [PacilRead] Connect a phone with USB debugging enabled, or install manually using:
echo [PacilRead]   app\build\outputs\apk\debug\app-debug.apk
exit /b 1

:main
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

if /I "%MODE%"=="install" call :preflight_install
if errorlevel 1 exit /b %errorlevel%

echo.
echo [PacilRead] Running Gradle task: %TASK%
call .\gradlew.bat %TASK% --no-daemon --console plain
if errorlevel 1 exit /b %errorlevel%

echo.
echo [PacilRead] Done: %TASK%
echo [PacilRead] Output: %OUTPUT%
exit /b 0
