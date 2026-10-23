$ErrorActionPreference = 'Stop'
$destination = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
if (Test-Path -LiteralPath $destination) {
    Write-Output '.env already exists; keeping the current credentials.'
    return
}

function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

$lines = @(
    "CONTROL_DATABASE_PASSWORD=$(New-Secret)"
    "CONTROL_PLANE_TOKEN=$(New-Secret)"
    "GRAFANA_ADMIN_PASSWORD=$(New-Secret)"
)
[System.IO.File]::WriteAllLines($destination, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created .env with random local credentials. Do not commit this file.'
