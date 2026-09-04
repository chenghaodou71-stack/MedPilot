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

if ($Production) {
    Check 'Redis shared-state configuration' {
        $redisEnabled = (Require 'MEDPILOT_REDIS_ENABLED').Trim().ToLowerInvariant()
        $redisRequired = (Require 'MEDPILOT_REDIS_REQUIRED').Trim().ToLowerInvariant()
        if ($redisEnabled -ne 'true' -or $redisRequired -ne 'true') {
            throw 'MEDPILOT_REDIS_ENABLED and MEDPILOT_REDIS_REQUIRED must both be true in production'
        }
        $redisUrl = Require 'MEDPILOT_REDIS_URL'
        if ($redisUrl -notmatch '^rediss?://[^\s]+$') {
            throw 'MEDPILOT_REDIS_URL must be a redis:// or rediss:// URL'
        }
        $hmac = Require 'MEDPILOT_REDIS_KEY_HMAC_SECRET' 32
        if ($hmac -match '(?i)replace-with|change-me|example|medpilot-dev') {
            throw 'MEDPILOT_REDIS_KEY_HMAC_SECRET contains a development placeholder'
        }
        $serviceToken = Require 'MEDPILOT_AI_SERVICE_TOKEN' 32
        if ($hmac -eq $serviceToken) {
            throw 'MEDPILOT_REDIS_KEY_HMAC_SECRET must be distinct from MEDPILOT_AI_SERVICE_TOKEN'
        }
    }
    Check 'Hospital OIDC and MFA configuration' {
        if ((Require 'MEDPILOT_OIDC_ENABLED').Trim().ToLowerInvariant() -ne 'true') {
            throw 'MEDPILOT_OIDC_ENABLED must be true; local password login is not an approved production identity source'
        }
        $issuer = [Uri](Require 'MEDPILOT_OIDC_ISSUER_URI')
        if (-not $issuer.IsAbsoluteUri -or $issuer.Scheme -ne 'https' -or [string]::IsNullOrWhiteSpace($issuer.Host)) {
            throw 'MEDPILOT_OIDC_ISSUER_URI must be an HTTPS URI'
        }
        if ($issuer.Host -match '(?i)(^localhost$|\.example$)') {
            throw 'MEDPILOT_OIDC_ISSUER_URI still points to a local or example identity provider'
        }
        Require 'MEDPILOT_OIDC_AUDIENCE' | Out-Null
        $mfaClaim = Require 'MEDPILOT_OIDC_MFA_CLAIM'
        if ($mfaClaim -notmatch '^[A-Za-z][A-Za-z0-9_.-]{0,63}$') {
            throw 'MEDPILOT_OIDC_MFA_CLAIM contains invalid characters'
        }
        $mfaLevel = 0
        $mfaParsed = [int]::TryParse((Require 'MEDPILOT_OIDC_REQUIRED_MFA_ASSURANCE_LEVEL'), [ref]$mfaLevel)
        if (-not $mfaParsed -or $mfaLevel -lt 2 -or $mfaLevel -gt 9) {
            throw 'MEDPILOT_OIDC_REQUIRED_MFA_ASSURANCE_LEVEL must be an integer from 2 to 9'
        }
        if ((Require 'MEDPILOT_LOCAL_PASSWORD_LOGIN_ENABLED').Trim().ToLowerInvariant() -eq 'true') {
            throw 'MEDPILOT_LOCAL_PASSWORD_LOGIN_ENABLED must be false in production'
        }
        $allowHttp = [Environment]::GetEnvironmentVariable('MEDPILOT_OIDC_ALLOW_INSECURE_HTTP')
        if (-not [string]::IsNullOrWhiteSpace($allowHttp) -and $allowHttp.Trim().ToLowerInvariant() -eq 'true') {
            throw 'MEDPILOT_OIDC_ALLOW_INSECURE_HTTP must remain false in production'
        }
    }
    Check 'Persisted consultation history source' {
        if ((Require 'MEDPILOT_SESSION_HISTORY_SOURCE').Trim().ToLowerInvariant() -notin @('backend', 'mysql', 'persisted')) {
            throw 'MEDPILOT_SESSION_HISTORY_SOURCE must be backend, mysql, or persisted in production'
        }
    }
    Check 'Frozen signed model release manifest' {
        $runtimeMode = Require 'MEDPILOT_RUNTIME_MODE'
        if ($runtimeMode -notin @('clinical', 'production')) { throw 'MEDPILOT_RUNTIME_MODE must be clinical or production' }
        Require 'MEDPILOT_MODEL_RELEASE_ID' | Out-Null
        Require 'MEDPILOT_MODEL_VERSION' | Out-Null
        Require 'MEDPILOT_MODEL_ARTIFACT_SIGNATURE' 16 | Out-Null
        Require 'MEDPILOT_MODEL_SIGNATURE_ALGORITHM' | Out-Null
        Require 'MEDPILOT_PROMPT_VERSION' | Out-Null
        Require 'MEDPILOT_EMBEDDING_VERSION' | Out-Null
        Require 'MEDPILOT_KNOWLEDGE_INDEX_VERSION' | Out-Null
        Require 'MEDPILOT_MODEL_SCOPE' | Out-Null
        $digest = Require 'MEDPILOT_MODEL_WEIGHT_SHA256' 64
        if ($digest -notmatch '^[0-9a-fA-F]{64}$') { throw 'MEDPILOT_MODEL_WEIGHT_SHA256 must be a 64-character hexadecimal digest' }
        if ((Require 'MEDPILOT_MODEL_RELEASE_STATUS') -ne 'FROZEN') { throw 'MEDPILOT_MODEL_RELEASE_STATUS must be FROZEN' }
    }
}

Check 'Backend health endpoint' {
    $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/health' -UseBasicParsing -TimeoutSec 5
    if ($response.StatusCode -ge 500) { throw "HTTP $($response.StatusCode)" }
    $body = $response.Content | ConvertFrom-Json
    if ($Production) {
        $sharedState = $body.data.shared_state
        if ($null -eq $sharedState -or $sharedState.ok -ne $true -or $sharedState.required -ne $true) {
            throw 'backend shared Redis state is not ready and required'
        }
    }
}
Check 'AI health endpoint' {
    $status = 0
    try {
        $headers = @{ 'X-MedPilot-Service-Token' = (Require 'MEDPILOT_AI_SERVICE_TOKEN' 32) }
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8000/health' -Headers $headers -UseBasicParsing -TimeoutSec 5
        $status = [int]$response.StatusCode
        if ($Production) {
            $body = $response.Content | ConvertFrom-Json
            if ($status -ne 200 -or $body.status -ne 'ok') {
                throw "AI health is not ready (HTTP $status, status $($body.status))"
            }
            $modelGovernance = $body.components.model_governance
            if ($null -eq $modelGovernance -or $modelGovernance.ok -ne $true) {
                throw 'AI model governance component is not ready'
            }
            if ($modelGovernance.clinical_mode -ne $true -or $modelGovernance.promotable -ne $true) {
                throw 'AI model governance component is not running in clinical mode'
            }
            if ($null -eq $modelGovernance.manifest -or $modelGovernance.manifest.status -ne 'FROZEN') {
                throw 'AI model governance component is not running a frozen clinical release'
            }
            $sharedState = $body.components.shared_state
            if ($null -eq $sharedState -or $sharedState.ok -ne $true -or $sharedState.required -ne $true) {
                throw 'AI shared Redis state is not ready and required'
            }
        }
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
