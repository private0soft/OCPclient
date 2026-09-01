@echo off
REM Bypass PowerShell npm.ps1 execution-policy blocks.
cd /d "%~dp0"
npm.cmd run tauri dev %*
