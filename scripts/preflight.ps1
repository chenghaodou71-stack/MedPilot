[CmdletBinding()]
param([switch]$Production)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'
$failures = [System.Collections.Generic.List[string]]::new()

function Check([string]$Label, [scriptblock]$Action) {
    $global:LASTEXITCODE = 0
    try {
        & $Action
        Write-Host "[OK]   $Label" -ForegroundColor Green
    } catch {
        $failures.Add("${Label}: $($_.Exception.Message)")
        Write-Host "[FAIL] $Label" -ForegroundColor Red
    }
}

function Require([string]$Name, [int]$MinimumLength = 1) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -lt $MinimumLength) { throw "$Name is missing or too short" }
    return $value
}

$root = Split-Path -Parent $PSScriptRoot
Check 'Java 17+' {
    $versionLine = (java -version 2>&1 | Select-Object -First 1).ToString()
    if ($versionLine -notmatch 'version "(?<version>[^"]+)"') { throw "unexpected version: $versionLine" }
    $versionParts = $Matches.version.Split('.')
    $major = if ($versionParts[0] -eq '1' -and $versionParts.Count -gt 1) {
        [int]$versionParts[1]
    } else {
        [int]$versionParts[0]
    }
    if ($major -lt 17) { throw "Java $major is below the required 17" }
}
Check 'Maven available' { if ($null -eq (Get-Command mvn.cmd -ErrorAction Stop)) { throw 'mvn.cmd missing' } }
Check 'Node and npm available' { if ($null -eq (Get-Command npm.cmd -ErrorAction Stop)) { throw 'npm.cmd missing' } }
Check 'Python virtualenv dependencies' {
    & (Join-Path $root 'ai-service\venv\Scripts\python.exe') -c 'import fastapi, uvicorn, langgraph, faiss'
    if ($LASTEXITCODE -ne 0) { throw "python dependency check exited with code $LASTEXITCODE" }
}

$profile = [Environment]::GetEnvironmentVariable('SPRING_PROFILES_ACTIVE')
if ($Production -and $profile -ne 'prod') { $failures.Add('SPRING_PROFILES_ACTIVE must be prod'); Write-Host '[FAIL] production profile is prod' -ForegroundColor Red } else { Write-Host "[OK]   profile ${profile}" -ForegroundColor Green }

Check 'Database configuration' {
    Require 'DB_URL' | Out-Null
    Require 'DB_USER' | Out-Null
    Require 'DB_PASSWORD' | Out-Null
}
Check 'AI service token' { $token = Require 'MEDPILOT_AI_SERVICE_TOKEN' 32; if ($token -match 'medpilot-dev|change-me|example') { throw 'development/default token is not allowed' } }
Check 'JWT secret' { $secret = Require 'JWT_SECRET' 32; if ($secret -match 'medpilot-dev|change-me|example') { throw 'development/default secret is not allowed' } }
Check 'AES encryption key' {
    $encoded = Require 'MEDPILOT_DATA_ENCRYPTION_KEY'
    $bytes = [Convert]::FromBase64String($encoded)
    if ($bytes.Length -ne 32) { throw 'must decode to exactly 32 bytes' }
}

Check 'Backend health endpoint' { $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health' -UseBasicParsing -TimeoutSec 5; if ($response.StatusCode -ge 500) { throw "HTTP $($response.StatusCode)" } }
Check 'AI health endpoint' {
    $status = 0
    try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8000/health' -UseBasicParsing -TimeoutSec 5
        $status = [int]$response.StatusCode
    }
    catch {
        if ($null -eq $_.Exception.Response -or $Production) { throw }
        $status = [int]$_.Exception.Response.StatusCode
    }
    if ($status -ge 500 -and $Production) { throw "HTTP $status" }
    if ($status -ge 500) { Write-Warning "AI service is degraded in development (HTTP $status)." }
}
Check 'Frontend build output' { if (-not (Test-Path -LiteralPath (Join-Path $root 'frontend\dist\index.html'))) { throw 'run npm.cmd run build first' } }

if ($failures.Count -gt 0) {
    Write-Host "`nPreflight failed with $($failures.Count) issue(s)." -ForegroundColor Red
    exit 1
}
Write-Host "`nPreflight passed. The deployment is ready for the next controlled step." -ForegroundColor Green
