#Requires -Version 7.0
$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (Test-Path -LiteralPath $target) { throw '.env already exists; preserve existing credentials and encryption key.' }
function New-RandomBytes {
    param([int]$Length = 32)
    $bytes = [byte[]]::new($Length)
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    return ,$bytes
}
$dbPassword = [Convert]::ToHexString((New-RandomBytes)).ToLowerInvariant()
$adminKey = [Convert]::ToHexString((New-RandomBytes)).ToLowerInvariant()
$monitorKey = [Convert]::ToHexString((New-RandomBytes)).ToLowerInvariant()
$encryptionKey = [Convert]::ToBase64String((New-RandomBytes))
$content = @"
DATABASE_PASSWORD=$dbPassword
ORBIT_PORT=18080
ORBIT_ADMIN_KEY=$adminKey
ORBIT_MONITORING_KEY=$monitorKey
ORBIT_ENCRYPTION_KEY=$encryptionKey
ORBIT_GLOBAL_BUDGET=107374182400
ORBIT_WARNING_PERCENT=80
ORBIT_BLOCKING_PERCENT=95
ORBIT_MAX_REQUEST_BYTES=104857600
ORBIT_RECONCILIATION_DELAY_MS=300000
ORBIT_DOCS_ENABLED=false
"@
[System.IO.File]::WriteAllText($target, $content, [System.Text.UTF8Encoding]::new($false))
Write-Output 'Created .env with fresh credentials. Keep it private and back it up. Start with docker compose up --build -d.'
