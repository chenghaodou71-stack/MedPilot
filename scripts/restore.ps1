[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$BackupPath,
    [switch]$ConfirmRestore,
    [switch]$SkipDatabase,
    [switch]$SkipAttachments,
    [string]$AttachmentDir = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $ConfirmRestore) { throw 'Restore is destructive. Re-run with -ConfirmRestore after checking the backup.' }

$projectRoot = Split-Path -Parent $PSScriptRoot
$backupDir = [IO.Path]::GetFullPath($BackupPath)
if (-not (Test-Path -LiteralPath $backupDir -PathType Container)) { throw "Backup directory not found: $backupDir" }
$manifestPath = Join-Path $backupDir 'manifest.json'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw 'manifest.json is required.' }
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($manifest.format -ne 'medpilot-backup-v1') { throw 'Unsupported backup format.' }

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

function Assert-SafeRestoreTarget([string]$Target) {
    $fullTarget = [IO.Path]::GetFullPath($Target)
    $normalized = $fullTarget.TrimEnd('\').TrimEnd('/')
    $driveRoot = ([IO.Path]::GetPathRoot($fullTarget)).TrimEnd('\').TrimEnd('/')
    $project = ([IO.Path]::GetFullPath($projectRoot)).TrimEnd('\').TrimEnd('/')
    $backend = ([IO.Path]::GetFullPath((Join-Path $projectRoot 'backend'))).TrimEnd('\').TrimEnd('/')
    $backup = $backupDir.TrimEnd('\').TrimEnd('/')
    if ($normalized -eq $driveRoot -or $normalized -eq $project -or $normalized -eq $backend) {
        throw "Unsafe attachment restore target: $fullTarget"
    }
    if ($backup -eq $normalized -or $backup.StartsWith($normalized + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Attachment restore target must not contain the selected backup directory.'
    }
}

foreach ($entry in @($manifest.files)) {
    $file = Join-Path $backupDir ([string]$entry.path)
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Backup file is missing: $($entry.path)" }
    $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne ([string]$entry.sha256).ToLowerInvariant()) { throw "Hash mismatch: $($entry.path)" }
}

function Require-Env([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ([string]::IsNullOrWhiteSpace($value)) { throw "Required environment variable '$Name' is missing." }
    return $value
}
function Resolve-Database([string]$JdbcUrl) {
    $match = [regex]::Match($JdbcUrl, '^jdbc:mysql://(?<host>[^:/?]+)(:(?<port>\d+))?/(?<db>[^?]+)')
    if (-not $match.Success) { throw 'DB_URL must be a MySQL JDBC URL.' }
    $database = $match.Groups['db'].Value
    if ($database -notmatch '^[A-Za-z0-9_$-]+$') { throw 'DB_URL contains an unsafe database name.' }
    return @{ Host = $match.Groups['host'].Value; Port = if ($match.Groups['port'].Success) { $match.Groups['port'].Value } else { '3306' }; Database = $database }
}

if (-not $SkipDatabase) {
    $db = Resolve-Database (Require-Env 'DB_URL')
    $dbUser = Require-Env 'DB_USER'
    $env:MYSQL_PWD = Require-Env 'DB_PASSWORD'
    $mysql = Get-Command mysql.exe -ErrorAction SilentlyContinue
    if ($null -eq $mysql) { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue; throw 'mysql.exe was not found in PATH.' }
    try {
        Get-Content -LiteralPath (Join-Path $backupDir 'database.sql') -Raw -Encoding UTF8 |
            & $mysql.Source --host=$db.Host --port=$db.Port --user=$dbUser $db.Database
        if ($LASTEXITCODE -ne 0) { throw "mysql restore failed with exit code $LASTEXITCODE." }
    }
    finally { Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue }
}

$indexBackup = Join-Path $backupDir 'ai-index'
$indexTarget = Join-Path $projectRoot 'ai-service\app\rag\index_store'
if (Test-Path -LiteralPath $indexBackup -PathType Container) {
    $staging = Join-Path $projectRoot ('.scratch\restore-index-' + [guid]::NewGuid().ToString('N'))
    Copy-Item -LiteralPath $indexBackup -Destination $staging -Recurse -Force
    if (Test-Path -LiteralPath $indexTarget) { Remove-Item -LiteralPath $indexTarget -Recurse -Force }
    Move-Item -LiteralPath $staging -Destination $indexTarget
}

if (-not $SkipAttachments) {
    $attachmentBackup = Join-Path $backupDir 'attachments'
    $attachmentTarget = Resolve-AttachmentDirectory $AttachmentDir
    Assert-SafeRestoreTarget $attachmentTarget
    if (Test-Path -LiteralPath $attachmentBackup -PathType Container) {
        New-Item -ItemType Directory -Path (Split-Path -Parent $attachmentTarget) -Force | Out-Null
        if (Test-Path -LiteralPath $attachmentTarget) { Remove-Item -LiteralPath $attachmentTarget -Recurse -Force }
        Copy-Item -LiteralPath $attachmentBackup -Destination $attachmentTarget -Recurse -Force
    }
}

Write-Host "Restore completed from: $backupDir"
Write-Host 'Restart AI and backend services and run scripts\preflight.ps1 before accepting traffic.'
