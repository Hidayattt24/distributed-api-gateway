param(
    [string]$Version = '0.1.0'
)

$ErrorActionPreference = 'Stop'
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Version must be x.y.z, for example 0.1.0'
}

$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    & .\gradlew.bat "-PreleaseVersion=$Version" check ':gateway:gateway-runtime:distZip' ':control-plane:bootJar' --no-daemon
    if ($LASTEXITCODE -ne 0) { throw 'Gradle release build failed' }

    $output = Join-Path $repo "build/release/$Version"
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $files = @(
        (Join-Path $repo "gateway/gateway-runtime/build/distributions/gateway-runtime-$Version.zip"),
        (Join-Path $repo "control-plane/build/libs/control-plane-$Version.jar")
    )
    foreach ($file in $files) {
        if (-not (Test-Path -LiteralPath $file)) { throw "Missing release artifact: $file" }
        Copy-Item -LiteralPath $file -Destination $output -Force
    }
    $lines = @($files | ForEach-Object {
        $name = Split-Path -Leaf $_
        $hash = (Get-FileHash -LiteralPath (Join-Path $output $name) -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    })
    Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Value $lines -Encoding ascii
    Write-Output "Release candidate ready: $output"
} finally {
    Pop-Location
}
