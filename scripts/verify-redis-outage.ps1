$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(8)

try {
    docker compose stop redis | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop Redis container.' }

    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $response = $client.GetAsync('http://localhost:8080/vets').GetAwaiter().GetResult()
    $timer.Stop()
    Write-Output "Redis stopped: /vets -> $([int]$response.StatusCode) in $([Math]::Round($timer.Elapsed.TotalSeconds, 2))s"
    if ([int]$response.StatusCode -ne 200) {
        throw 'Expected local fallback to allow this request while Redis is stopped.'
    }
    Write-Output 'Redis outage fallback PASS'
} finally {
    docker compose start redis | Out-Null
    $client.Dispose()
}
