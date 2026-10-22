param(
    [string]$BaseUrl = 'http://localhost:8080',
    [int]$ReadyTimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)

try {
    $deadline = [DateTime]::UtcNow.AddSeconds($ReadyTimeoutSeconds)
    do {
        try {
            $ready = $client.GetAsync("$BaseUrl/vets").GetAwaiter().GetResult()
            if ($ready.StatusCode -eq [System.Net.HttpStatusCode]::OK) { break }
        } catch { }
        Start-Sleep -Seconds 3
    } while ([DateTime]::UtcNow -lt $deadline)

    if ($ready.StatusCode -ne [System.Net.HttpStatusCode]::OK) {
        throw 'Gateway or PetClinic services did not become ready in time.'
    }

    foreach ($path in @('/owners/1', '/vets', '/pets/visits?petId=1')) {
        $response = $client.GetAsync("$BaseUrl$path").GetAwaiter().GetResult()
        Write-Output "$path -> $([int]$response.StatusCode)"
        if ([int]$response.StatusCode -ne 200) { throw "Unexpected status for $path" }
    }

    $missing = $client.GetAsync("$BaseUrl/no-such-route").GetAwaiter().GetResult()
    Write-Output "/no-such-route -> $([int]$missing.StatusCode)"
    if ([int]$missing.StatusCode -ne 404) { throw 'Unmatched route should return 404.' }

    $requests = @()
    for ($i = 0; $i -lt 60; $i++) {
        $requests += $client.GetAsync("$BaseUrl/vets")
    }
    [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$requests)
    $limited = @($requests | Where-Object { [int]$_.Result.StatusCode -eq 429 }).Count
    Write-Output "Burst of 60 to /vets -> $limited responses with 429"
    if ($limited -lt 1) { throw 'Expected gateway to reject at least one burst request.' }
    Write-Output 'PetClinic end-to-end verification PASS'
} finally {
    $client.Dispose()
}
