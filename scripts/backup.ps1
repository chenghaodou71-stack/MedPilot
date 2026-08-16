[CmdletBinding()]
param(
    [string]$OutputDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'backups'),
    [int]$RetentionDays = 30,
    [string]$AttachmentDir = '',
    [switch]$IncludeAttachments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$OutputDir = [IO.Path]::GetFullPath($OutputDir)
if ($RetentionDays -lt 1) { throw 'RetentionDays must be at least 1.' }
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

function Resolve-AttachmentDirectory([string]$ExplicitPath) {
    $configured = $ExplicitPath
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $configured = [Environment]::GetEnvironmentVariable('MEDPILOT_ATTACHMENTS_STORAGE_DIR')
    }
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $configured = [Environment]::GetEnvironmentVariable('MEDPILOT_ATTACHMENT_DIR')
    }
    if ([string]::IsNullOrWhiteSpace($configured)) {
        $configured = '.\var\private\attachments'
    }
    if ([IO.Path]::IsPathRooted($configured)) {
        return [IO.Path]::GetFullPath($configured)
    }
    return [IO.Path]::GetFullPath((Join-Path (Join-Path $projectRoot 'backend') $configured))
}

function Require-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required environment variable '$Name' is missing." }
    return $value
}

function Resolve-Database([string]$JdbcUrl) {
    $match = [regex]::Match($JdbcUrl, '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<db>[^?]+)')
    if (-not $match.Success) { throw 'DB_URL must be a MySQL JDBC URL (jdbc:mysql://host[:port]/database).' }
    $database = $match.Groups['db'].Value
    if ($database -notmatch '^[A-Za-z0-9_$-]+$') { throw 'DB_URL contains an unsafe database name.' }
    return @{
        Host = $match.Groups['host'].Value
        Port = if ($match.Groups['port'].Success) { $match.Groups['port'].Value } else { '3306' }
        Database = $database
    }
}

function Write-HashManifest([string]$Directory) {
    $entries = Get-ChildItem -LiteralPath $Directory -File -Recurse |
        Where-Object { $_.Name -ne 'manifest.json' } |
        ForEach-Object {
            [pscustomobject]@{
                path = $_.FullName.Substring($Directory.Length).TrimStart('\', '/')
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                bytes = $_.Length
            }
        }
    [pscustomobject]@{
        format = 'medpilot-backup-v1'
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
        encryptedMedicalData = $true
        files = @($entries)
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $Directory 'manifest.json') -Encoding UTF8
}

$dbUrl = Require-Env 'DB_URL'
$dbUser = Require-Env 'DB_USER'
$dbPassword = Require-Env 'DB_PASSWORD'
$db = Resolve-Database $dbUrl
$mysqldump = Get-Command mysqldump.exe -ErrorAction SilentlyContinue
if ($null -eq $mysqldump) { throw 'mysqldump.exe was not found in PATH.' }

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmssZ')
$backupDir = Join-Path $OutputDir "medpilot-$stamp"
New-Item -ItemType Directory -Path $backupDir -Force | Out-Null
$env:MYSQL_PWD = $dbPassword
try {
    & $mysqldump.Source --host=$db.Host --port=$db.Port --user=$dbUser `
        --single-transaction --routines --triggers --hex-blob --set-gtid-purged=OFF `
        --result-file=(Join-Path $backupDir 'database.sql') $db.Database
    if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE." }
}
finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}

$indexSource = Join-Path $projectRoot 'ai-service\app\rag\index_store'
if (Test-Path -LiteralPath $indexSource) {
    Copy-Item -LiteralPath $indexSource -Destination (Join-Path $backupDir 'ai-index') -Recurse -Force
} else {
    Write-Warning "AI index directory not found: $indexSource"
}

if ($IncludeAttachments) {
    $attachmentSource = Resolve-AttachmentDirectory $AttachmentDir
    if (Test-Path -LiteralPath $attachmentSource) {
        Copy-Item -LiteralPath $attachmentSource -Destination (Join-Path $backupDir 'attachments') -Recurse -Force
    } else {
        Write-Warning "Attachment directory not found: $attachmentSource"
    }
}

Write-HashManifest -Directory $backupDir
$cutoff = (Get-Date).ToUniversalTime().AddDays(-$RetentionDays)
Get-ChildItem -LiteralPath $OutputDir -Directory -Filter 'medpilot-*' |
    Where-Object { $_.LastWriteTimeUtc -lt $cutoff -and $_.FullName -ne $backupDir } |
    ForEach-Object { Remove-Item -LiteralPath $_.FullName -Recurse -Force }

Write-Host "Backup created: $backupDir"
Write-Host 'Medical payloads remain encrypted at rest; manifest hashes cover every backup file.'
