@echo off
setlocal
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\desktop.ps1" -Action backup %*
exit /b %errorlevel%
