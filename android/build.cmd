@echo off
setlocal EnableExtensions EnableDelayedExpansion
REM =============================================================================
REM OpenConnect +P — Android release builder (Windows)
REM
REM Edit the three values below, then run:  build.cmd
REM Or:  build.cmd --version 1.0.5 --code 105 --url https://host/android/ap.json
REM =============================================================================

cd /d "%~dp0"

call :find_java
if not defined JAVA_HOME (
  echo ERROR: JDK 17 was not found. Install Microsoft OpenJDK 17 or set JAVA_HOME.
  exit /b 1
)
echo   JAVA_HOME    %JAVA_HOME%

if not defined ANDROID_HOME (
  if exist "%LOCALAPPDATA%\Android\Sdk" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
)
if defined ANDROID_HOME echo   ANDROID_HOME %ANDROID_HOME%

if not exist "%~dp0gradlew.bat" (
  echo ERROR: gradlew.bat is missing
  exit /b 1
)
if not exist "%~dp0gradle\wrapper\gradle-wrapper.jar" (
  echo ERROR: gradle-wrapper.jar is missing
  exit /b 1
)
if not exist "%~dp0app\src\main\jniLibs\arm64-v8a\libopenconnect.so" (
  echo ERROR: native libs missing: app\src\main\jniLibs\arm64-v8a\libopenconnect.so
  exit /b 1
)
if not exist "%~dp0app\src\main\jniLibs\arm64-v8a\libstoken.so" (
  echo ERROR: native libs missing: app\src\main\jniLibs\arm64-v8a\libstoken.so
  exit /b 1
)

REM ---------- release values (edit these) ----------
set "VERSION=1.0.5"
set "VERSION_CODE=105"
set "UPDATE_URL=https://raw.githubusercontent.com/private0soft/OCPclient/refs/heads/main/android/ap.json"
set "NOTES=Latest Android release"
set "APK_URL="
set "SKIP_BAKE=0"
REM -------------------------------------------------

:parse_args
if "%~1"=="" goto args_done
if /I "%~1"=="--version" (
  set "VERSION=%~2"
  shift & shift
  goto parse_args
)
if /I "%~1"=="--code" (
  set "VERSION_CODE=%~2"
  shift & shift
  goto parse_args
)
if /I "%~1"=="--url" (
  set "UPDATE_URL=%~2"
  shift & shift
  goto parse_args
)
if /I "%~1"=="--notes" (
  set "NOTES=%~2"
  shift & shift
  goto parse_args
)
if /I "%~1"=="--apk-url" (
  set "APK_URL=%~2"
  shift & shift
  goto parse_args
)
if /I "%~1"=="--skip-bake" (
  set "SKIP_BAKE=1"
  shift
  goto parse_args
)
if /I "%~1"=="-h" goto usage
if /I "%~1"=="--help" goto usage
echo Unknown option: %~1
goto usage

:usage
echo Usage: build.cmd [--version NAME] [--code N] [--url HTTPS_JSON] [--apk-url HTTPS_APK]
exit /b 1

:args_done
if "%VERSION%"=="" (
  echo VERSION is empty
  exit /b 1
)
if "%VERSION_CODE%"=="" (
  echo VERSION_CODE is empty
  exit /b 1
)
echo %VERSION_CODE%| findstr /R "^[0-9][0-9]*$" >nul
if errorlevel 1 (
  echo VERSION_CODE must be an integer
  exit /b 1
)
if "%UPDATE_URL%"=="" (
  echo UPDATE_URL is empty
  exit /b 1
)
echo %UPDATE_URL% | findstr /I /B "https://" >nul
if errorlevel 1 (
  echo UPDATE_URL must start with https://
  exit /b 1
)

if "%APK_URL%"=="" (
  set "APK_URL=%UPDATE_URL%"
  if /I "!APK_URL:~-7!"=="ap.json" set "APK_URL=!APK_URL:~0,-7!OpenConnect-P_latest.apk"
)

echo.
echo OpenConnect +P — Android release
echo   versionName  %VERSION%
echo   versionCode  %VERSION_CODE%
echo   update JSON  (XOR-baked into UpdateDefaults — not plaintext)
echo   apk url      %APK_URL%
echo.

if "%SKIP_BAKE%"=="0" (
  set "PY="
  where python >nul 2>&1 && set "PY=python"
  if not defined PY (
    where python3 >nul 2>&1 && set "PY=python3"
  )
  if not defined PY (
    echo ERROR: python is required to bake the update URL
    exit /b 1
  )
  %PY% "%~dp0bake_update_url.py" "%UPDATE_URL%" "%~dp0app\src\main\java\net\openconnect_vpn\android\core\UpdateDefaults.java"
  if errorlevel 1 (
    echo ERROR: failed to bake update URL
    exit /b 1
  )
)

set "OCP_VERSION=%VERSION%"
set "OCP_VERSION_CODE=%VERSION_CODE%"
set "OCP_UPDATE_URL="

call gradlew.bat :app:assembleRelease
if errorlevel 1 (
  echo Build failed
  exit /b 1
)

set "SRC=%~dp0app\build\outputs\apk\release\app-release.apk"
if not exist "%SRC%" set "SRC=%~dp0app\build\outputs\apk\release\app-release-unsigned.apk"
set "DST=%~dp0..\android\OpenConnect-P-latest-release.apk"

if not exist "%SRC%" (
  echo ERROR: release APK not found under app\build\outputs\apk\release
  exit /b 1
)

if exist "%DST%" del /f /q "%DST%"
move /Y "%SRC%" "%DST%" >nul
if errorlevel 1 (
  echo ERROR: could not move APK to %DST%
  exit /b 1
)

(
  echo {
  echo   "versionCode": %VERSION_CODE%,
  echo   "versionName": "%VERSION%",
  echo   "notes": "%NOTES%",
  echo   "url": "%APK_URL%"
  echo }
) > "%~dp0ap.json"

if exist "%~dp0..\ap.json" (
  (
    echo {
    echo   "versionCode": %VERSION_CODE%,
    echo   "versionName": "%VERSION%",
    echo   "notes": "%NOTES%",
    echo   "url": "%APK_URL%"
    echo }
  ) > "%~dp0..\ap.json"
)

echo.
echo Done.
echo   APK:      %DST%
echo   Manifest: %~dp0ap.json
echo   Upload ap.json to: %UPDATE_URL%
echo   Upload APK to:     %APK_URL%
echo %SRC% | findstr /I unsigned >nul
if not errorlevel 1 (
  echo.
  echo NOTE: APK is unsigned. Put myoc-release.jks in android\app
)
exit /b 0

:find_java
if defined JAVA_HOME (
  if exist "%JAVA_HOME%\bin\java.exe" goto :eof
)
set "JAVA_HOME="
if exist "%USERPROFILE%\.jdks\jdk-17\bin\java.exe" (
  set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-17"
  goto :eof
)
for /d %%J in ("%ProgramFiles%\Microsoft\jdk-17*") do (
  if exist "%%J\bin\java.exe" (
    set "JAVA_HOME=%%J"
    goto :eof
  )
)
for /d %%J in ("%ProgramFiles%\Eclipse Adoptium\jdk-17*") do (
  if exist "%%J\bin\java.exe" (
    set "JAVA_HOME=%%J"
    goto :eof
  )
)
for /d %%J in ("%ProgramFiles%\Java\jdk-17*") do (
  if exist "%%J\bin\java.exe" (
    set "JAVA_HOME=%%J"
    goto :eof
  )
)
goto :eof
