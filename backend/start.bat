@echo off
setlocal
cd /d "%~dp0"

if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=dev"

if not exist "target\medpilot-backend-0.1.0.jar" (
    call mvn package -DskipTests
    if errorlevel 1 exit /b %errorlevel%
)

java -jar "target\medpilot-backend-0.1.0.jar"
