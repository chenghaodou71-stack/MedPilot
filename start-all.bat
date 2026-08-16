@echo off
setlocal
cd /d "%~dp0"

if /I "%~1"=="stop" (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" -Stop
) else (
    powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-all.ps1" %*
)

set "medpilotExitCode=%errorlevel%"
if not "%medpilotExitCode%"=="0" (
    echo.
    echo MedPilot startup failed. Review the message above and .scratch\run logs.
    pause
)

endlocal & exit /b %medpilotExitCode%
