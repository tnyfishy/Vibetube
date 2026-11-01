@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\debug.ps1" %*
exit /b %errorlevel%
