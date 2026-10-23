@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\desktop.ps1" -Action stop %*
exit /b %errorlevel%
