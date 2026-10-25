$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$template = Join-Path $repo 'deploy/production/.env.example'
$destination = Join-Path $repo 'deploy/production/.env'
if (Test-Path -LiteralPath $destination) {
    Write-Output 'deploy/production/.env already exists; keeping its current values.'
    return
}

function New-Secret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

$apiKey = New-Secret
$sha = [System.Security.Cryptography.SHA256]::Create()
try {
    $hash = ([BitConverter]::ToString($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($apiKey)))).Replace('-', '').ToLowerInvariant()
} finally {
    $sha.Dispose()
}

$lines = [string[]](Get-Content -LiteralPath $template)
$generated = @{
    CONTROL_PLANE_TOKEN = New-Secret
    API_KEY_SECRET = $apiKey
    RATE_LIMIT_API_API_KEY_SHA256 = $hash
}
foreach ($key in $generated.Keys) {
    $index = -1
    for ($i = 0; $i -lt $lines.Length; $i++) {
        if ($lines[$i].StartsWith("$key=")) { $index = $i; break }
    }
    if ($index -lt 0) { throw "Missing $key in .env.example" }
    $lines[$index] = "$key=$($generated[$key])"
}
[System.IO.File]::WriteAllLines($destination, $lines, (New-Object System.Text.UTF8Encoding($false)))
Write-Output 'Created ignored deploy/production/.env. Fill CONTROL_DATABASE_PASSWORD and real service/image addresses before deployment.'
