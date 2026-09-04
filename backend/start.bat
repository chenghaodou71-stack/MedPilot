@echo off
setlocal
cd /d "%~dp0"

if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=dev"

set "needsBuild=0"
if not exist "target\medpilot-backend-0.1.0.jar" set "needsBuild=1"

if "%needsBuild%"=="0" (
    jar tf "target\medpilot-backend-0.1.0.jar" 2>nul | findstr /x /c:"org/springframework/boot/loader/launch/JarLauncher.class" >nul || set "needsBuild=1"
)
if "%needsBuild%"=="0" (
    jar tf "target\medpilot-backend-0.1.0.jar" 2>nul | findstr /x /c:"BOOT-INF/classes/com/medpilot/MedPilotApplication.class" >nul || set "needsBuild=1"
)

if "%needsBuild%"=="1" (
    echo Building Spring Boot backend...
    call mvn clean package -DskipTests
    if errorlevel 1 exit /b 1
)

jar tf "target\medpilot-backend-0.1.0.jar" 2>nul | findstr /x /c:"org/springframework/boot/loader/launch/JarLauncher.class" >nul
if errorlevel 1 (
    echo Backend JAR is not a runnable Spring Boot archive.
    exit /b 1
)

java -jar "target\medpilot-backend-0.1.0.jar"
