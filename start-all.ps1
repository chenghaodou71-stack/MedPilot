[CmdletBinding()]
param(
    [switch]$Stop,
    [switch]$Rebuild,
    [switch]$NoBrowser
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$RuntimeDir = Join-Path $ProjectRoot '.scratch\run'
$StateFile = Join-Path $RuntimeDir 'medpilot-processes.json'
$FrontendUrl = 'http://127.0.0.1:5173/'
$AiUrl = 'http://127.0.0.1:8000/'
$BackendHealthUrl = 'http://127.0.0.1:8080/api/health'

function Write-Step {
    param([string]$Message)
    Write-Host "[MedPilot] $Message" -ForegroundColor Cyan
}

function Test-ListeningPort {
    param([int]$Port)
    return [bool](Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
}

function Get-RequiredCommand {
    param([string]$Name)
    $command = Get-Command $Name -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($null -eq $command) {
        throw "Required command '$Name' was not found in PATH."
    }
    return $command.Source
}

function Get-ManagedProcesses {
    if (-not (Test-Path -LiteralPath $StateFile)) {
        return @()
    }

    try {
        $state = Get-Content -LiteralPath $StateFile -Raw -Encoding UTF8 | ConvertFrom-Json
        $active = @()
        foreach ($entry in @($state.processes)) {
            $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
            if ($null -ne $process -and $process.ProcessName -eq [string]$entry.processName) {
                $active += $entry
            }
        }
        return $active
    }
    catch {
        Write-Warning "Ignoring unreadable process state: $StateFile"
        return @()
    }
}

function Save-ManagedProcesses {
    param([object[]]$Processes)

    if ($Processes.Count -eq 0) {
        if (Test-Path -LiteralPath $StateFile) {
            Remove-Item -LiteralPath $StateFile -Force
        }
        return
    }

    New-Item -ItemType Directory -Path $RuntimeDir -Force | Out-Null
    @{
        startedAt = (Get-Date).ToString('o')
        processes = @($Processes)
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $StateFile -Encoding UTF8
}

function Stop-ManagedProcessEntries {
    param([object[]]$Processes)

    foreach ($entry in @($Processes | Sort-Object -Property port -Descending)) {
        $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
        if ($null -eq $process) {
            continue
        }
        if ($process.ProcessName -ne [string]$entry.processName) {
            Write-Warning "Skipping PID $($entry.pid): process identity no longer matches $($entry.name)."
            continue
        }

        Write-Step "Stopping $($entry.name) (PID $($entry.pid))"
        & taskkill.exe /PID ([int]$entry.pid) /T /F 2>$null | Out-Null
    }
}

function Start-LoggedProcess {
    param(
        [string]$Name,
        [int]$Port,
        [string]$FilePath,
        [string[]]$ArgumentList,
        [string]$WorkingDirectory,
        [string]$ProcessName
    )

    New-Item -ItemType Directory -Path $RuntimeDir -Force | Out-Null
    $logBase = $Name.ToLowerInvariant().Replace(' ', '-')
    $stdout = Join-Path $RuntimeDir "$logBase.out.log"
    $stderr = Join-Path $RuntimeDir "$logBase.err.log"
    Set-Content -LiteralPath $stdout -Value '' -Encoding UTF8
    Set-Content -LiteralPath $stderr -Value '' -Encoding UTF8

    $process = Start-Process `
        -FilePath $FilePath `
        -ArgumentList $ArgumentList `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    return [PSCustomObject]@{
        name = $Name
        pid = $process.Id
        processName = $ProcessName
        port = $Port
        stdout = $stdout
        stderr = $stderr
    }
}

function Wait-ForHttp {
    param(
        [string]$Name,
        [string]$Uri,
        [int]$TimeoutSeconds,
        [Nullable[int]]$ManagedPid,
        [string]$ErrorLog
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $ManagedPid) {
            # PowerShell unwraps a non-null Nullable[int] during parameter binding.
            $process = Get-Process -Id ([int]$ManagedPid) -ErrorAction SilentlyContinue
            if ($null -eq $process) {
                $tail = ''
                if ($ErrorLog -and (Test-Path -LiteralPath $ErrorLog)) {
                    $tail = (Get-Content -LiteralPath $ErrorLog -Tail 12 -ErrorAction SilentlyContinue) -join [Environment]::NewLine
                }
                throw "$Name exited before becoming ready.`n$tail"
            }
        }

        try {
            $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Step "$Name is ready: $Uri"
                return
            }
        }
        catch {
            # The service can reject connections while it is still starting.
        }
        Start-Sleep -Milliseconds 500
    }

    throw "$Name did not become ready within $TimeoutSeconds seconds. See $ErrorLog"
}

if ($Stop) {
    $managed = @(Get-ManagedProcesses)
    if ($managed.Count -eq 0) {
        Write-Step 'No managed MedPilot processes are running.'
        exit 0
    }
    Stop-ManagedProcessEntries -Processes $managed
    Save-ManagedProcesses -Processes @()
    Write-Step 'Managed MedPilot services stopped.'
    exit 0
}

$AiDir = Join-Path $ProjectRoot 'ai-service'
$BackendDir = Join-Path $ProjectRoot 'backend'
$FrontendDir = Join-Path $ProjectRoot 'frontend'
$PythonExe = Join-Path $AiDir 'venv\Scripts\python.exe'
$BackendJar = Join-Path $BackendDir 'target\medpilot-backend-0.1.0.jar'
$ViteExe = Join-Path $FrontendDir 'node_modules\.bin\vite.cmd'

if (-not (Test-Path -LiteralPath $PythonExe)) {
    throw "Python virtual environment is missing: $PythonExe. Follow README setup first."
}
if (-not (Test-Path -LiteralPath $ViteExe)) {
    throw "Frontend dependencies are missing. Run 'npm.cmd install' in $FrontendDir."
}

$JavaExe = Get-RequiredCommand 'java.exe'
$NpmExe = Get-RequiredCommand 'npm.cmd'

if ($Rebuild -or -not (Test-Path -LiteralPath $BackendJar)) {
    $MavenExe = Get-RequiredCommand 'mvn.cmd'
    Write-Step 'Building Spring Boot backend...'
    Push-Location $BackendDir
    try {
        & $MavenExe package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

& $PythonExe -c 'import fastapi, uvicorn, langgraph, faiss' 2>$null
if ($LASTEXITCODE -ne 0) {
    throw "AI service dependencies are incomplete. Install $AiDir\requirements.txt."
}

if (-not $env:DB_URL -and -not (Test-ListeningPort 3306)) {
    Write-Warning 'MySQL is not listening on 127.0.0.1:3306; the backend may fail to start.'
}
if (-not (Test-ListeningPort 11434)) {
    Write-Warning "Ollama is not listening on 127.0.0.1:11434; AI readiness will be degraded."
}
else {
    try {
        $tags = Invoke-RestMethod -Uri 'http://127.0.0.1:11434/api/tags' -TimeoutSec 3
        $models = @($tags.models | ForEach-Object { $_.name })
        foreach ($requiredModel in @('qwen2.5:7b', 'bge-m3')) {
            $modelInstalled = $models | Where-Object {
                $_ -eq $requiredModel -or $_ -like ($requiredModel + ':*')
            }
            if ($null -eq $modelInstalled) {
                Write-Warning "Ollama model '$requiredModel' is not installed."
            }
        }
    }
    catch {
        Write-Warning 'Ollama is listening but its model list could not be read.'
    }
}

$env:SPRING_PROFILES_ACTIVE = 'dev'
if ([string]::IsNullOrWhiteSpace($env:MEDPILOT_AI_SERVICE_TOKEN)) {
    $env:MEDPILOT_AI_SERVICE_TOKEN = 'medpilot-dev-service-token'
}
$env:MEDPILOT_AI_SERVICE_URL = 'http://127.0.0.1:8000'
$env:PYTHONUNBUFFERED = '1'

$existingManaged = @(Get-ManagedProcesses)
$newManaged = @()

try {
    $aiEntry = $null
    if (Test-ListeningPort 8000) {
        Write-Step 'AI service already uses port 8000; reusing it.'
    }
    else {
        Write-Step 'Starting AI service on port 8000...'
        $aiEntry = Start-LoggedProcess `
            -Name 'AI Service' `
            -Port 8000 `
            -FilePath $PythonExe `
            -ArgumentList @('-m', 'uvicorn', 'main:app', '--host', '127.0.0.1', '--port', '8000') `
            -WorkingDirectory $AiDir `
            -ProcessName 'python'
        $newManaged += $aiEntry
    }
    Wait-ForHttp -Name 'AI service' -Uri $AiUrl -TimeoutSeconds 30 `
        -ManagedPid $(if ($aiEntry) { [int]$aiEntry.pid } else { $null }) `
        -ErrorLog $(if ($aiEntry) { [string]$aiEntry.stderr } else { '' })

    $backendEntry = $null
    if (Test-ListeningPort 8080) {
        Write-Step 'Backend already uses port 8080; reusing it.'
    }
    else {
        Write-Step 'Starting Spring Boot backend on port 8080...'
        $backendEntry = Start-LoggedProcess `
            -Name 'Backend' `
            -Port 8080 `
            -FilePath $JavaExe `
            -ArgumentList @('-jar', 'target\medpilot-backend-0.1.0.jar') `
            -WorkingDirectory $BackendDir `
            -ProcessName 'java'
        $newManaged += $backendEntry
    }
    Wait-ForHttp -Name 'Backend' -Uri $BackendHealthUrl -TimeoutSeconds 90 `
        -ManagedPid $(if ($backendEntry) { [int]$backendEntry.pid } else { $null }) `
        -ErrorLog $(if ($backendEntry) { [string]$backendEntry.stderr } else { '' })

    $frontendEntry = $null
    if (Test-ListeningPort 5173) {
        Write-Step 'Frontend already uses port 5173; reusing it.'
    }
    else {
        Write-Step 'Starting Vite frontend on port 5173...'
        $frontendEntry = Start-LoggedProcess `
            -Name 'Frontend' `
            -Port 5173 `
            -FilePath $env:ComSpec `
            -ArgumentList @('/d', '/s', '/c', 'npm.cmd run dev -- --host 127.0.0.1') `
            -WorkingDirectory $FrontendDir `
            -ProcessName 'cmd'
        $newManaged += $frontendEntry
    }
    Wait-ForHttp -Name 'Frontend' -Uri $FrontendUrl -TimeoutSeconds 30 `
        -ManagedPid $(if ($frontendEntry) { [int]$frontendEntry.pid } else { $null }) `
        -ErrorLog $(if ($frontendEntry) { [string]$frontendEntry.stderr } else { '' })

    $allManaged = @($existingManaged + $newManaged)
    Save-ManagedProcesses -Processes $allManaged

    Write-Host ''
    Write-Host 'MedPilot is running.' -ForegroundColor Green
    Write-Host "Frontend: $FrontendUrl"
    Write-Host "Backend:  $BackendHealthUrl"
    Write-Host "AI:       $AiUrl"
    Write-Host 'Demo accounts (dev only): admin/admin123, user/user123, editor/editor123, reviewer/reviewer123, doctor/doctor123, auditor/auditor123'
    Write-Host "Logs: $RuntimeDir"
    Write-Host 'Stop: .\start-all.bat stop'

    if (-not $NoBrowser) {
        Start-Process $FrontendUrl | Out-Null
    }
}
catch {
    $failureMessage = $_.Exception.Message
    try {
        Stop-ManagedProcessEntries -Processes $newManaged
    }
    catch {
        Write-Warning "Could not clean up all newly started processes: $($_.Exception.Message)"
    }
    Save-ManagedProcesses -Processes $existingManaged
    Write-Error $failureMessage -ErrorAction Continue
    exit 1
}
