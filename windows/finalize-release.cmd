@echo off
REM Post-build only: move NSIS exe + refresh ex.json.
REM Use build.cmd for a full release. This is for manual "npm run tauri:build".
cd /d "%~dp0"
call build.cmd :finalize_only
exit /b %ERRORLEVEL%
