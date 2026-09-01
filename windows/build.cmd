@echo off
setlocal EnableExtensions
REM Build release installer (NSIS).
REM Fill the three values below, then run:  build.cmd

cd /d "%~dp0"

REM ---------- release values (edit these) ----------
set "VERSION=1.0.4"
set "VERSION_CODE=104"
set "UPDATE_URL=https://raw.githubusercontent.com/private0soft/OCPclient/refs/heads/main/windows/ex.json"
REM -------------------------------------------------

if /i "%~1"==":finalize_only" goto finalize_release

if "%VERSION%"=="" (
  echo VERSION is empty. Set it at the top of build.cmd
  exit /b 1
)
if "%VERSION_CODE%"=="" (
  echo VERSION_CODE is empty. Use an integer, e.g. 103 for 1.0.3
  exit /b 1
)
if "%UPDATE_URL%"=="" (
  echo UPDATE_URL is empty. Set the https JSON update link at the top of build.cmd
  exit /b 1
)
echo %UPDATE_URL% | findstr /I /B "https://" >nul
if errorlevel 1 (
  echo UPDATE_URL must start with https://
  exit /b 1
)

if not exist "vendor\openconnect\bin\libopenconnect-5.dll" (
  echo Missing vendor OpenConnect DLLs. Run: powershell -File scripts\fetch-native.ps1
  exit /b 1
)

set "OCP_VERSION=%VERSION%"
set "OCP_VERSION_CODE=%VERSION_CODE%"
set "OCP_UPDATE_URL=%UPDATE_URL%"

set "TAURI_OVERRIDE=%TEMP%\ocp-tauri-override.json"
> "%TAURI_OVERRIDE%" echo {"version":"%VERSION%"}

echo.
echo Building OpenConnect +P
echo   version      %VERSION%
echo   versionCode  %VERSION_CODE%
echo   update URL   (hidden in the binary)
echo   (app UI subtitle uses this VERSION, not Cargo.toml)
echo.

REM "call" is required — npx.cmd is a batch file; without it post-build steps never run.
call npx.cmd tauri build --config "%TAURI_OVERRIDE%" %*
set "ERR=%ERRORLEVEL%"
del /q "%TAURI_OVERRIDE%" 2>nul
if not "%ERR%"=="0" (
  echo Build failed with exit code %ERR%.
  exit /b %ERR%
)

call :finalize_release
exit /b %ERRORLEVEL%

:finalize_release
set "NSIS_DIR=%~dp0src-tauri\target\release\bundle\nsis"
set "STABLE_SETUP=%~dp0OpenConnect +P_latest_x64-setup.exe"
set "BUILT_EXE="

if not exist "%NSIS_DIR%" (
  echo ERROR: NSIS folder not found:
  echo   %NSIS_DIR%
  exit /b 1
)

pushd "%NSIS_DIR%"
for %%F in (*.exe) do set "BUILT_EXE=%%~fF"
popd

if not defined BUILT_EXE (
  echo ERROR: no installer .exe in:
  echo   %NSIS_DIR%
  exit /b 1
)

if /I "%BUILT_EXE%"=="%STABLE_SETUP%" (
  echo Installer already at stable path.
) else (
  if exist "%STABLE_SETUP%" del /f /q "%STABLE_SETUP%"
  move /Y "%BUILT_EXE%" "%STABLE_SETUP%"
  if errorlevel 1 (
    echo ERROR: could not move installer to:
    echo   %STABLE_SETUP%
    exit /b 1
  )
  echo Moved installer -^> OpenConnect +P_latest_x64-setup.exe
  echo Removed NSIS output from target\release\bundle\nsis\
)

echo.
echo GitHub download file:
echo   %STABLE_SETUP%

set "DOWNLOAD_URL=%UPDATE_URL%"
if /I "%DOWNLOAD_URL:~-7%"=="ex.json" set "DOWNLOAD_URL=%DOWNLOAD_URL:~0,-7%"
set "DOWNLOAD_URL=%DOWNLOAD_URL%windows/OpenConnect%%20+P_latest_x64-setup.exe"
call :write_ex "%~dp0ex.json"
if exist "%~dp0..\ex.json" call :write_ex "%~dp0..\ex.json"
echo Updated ex.json  versionCode=%VERSION_CODE%  versionName=%VERSION%
exit /b 0

:write_ex
(
  echo {
  echo   "versionCode": %VERSION_CODE%,
  echo   "versionName": "%VERSION%",
  echo   "notes": "Latest Windows release",
  echo   "url": "%DOWNLOAD_URL%"
  echo }
) > "%~1"
exit /b 0
