param(
    [string]$GatewayUrl = 'http://localhost:8080',
    [string]$PrometheusUrl = 'http://localhost:9091'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$client = [System.Net.Http.HttpClient]::new()
$client.Timeout = [TimeSpan]::FromSeconds(10)

try {
    Write-Output "1. Checking Gateway Health Endpoint ($GatewayUrl/_gateway/health)..."
    $healthRes = $null
    for ($attempt = 1; $attempt -le 10; $attempt++) {
        try {
            $healthRes = $client.GetAsync("$GatewayUrl/_gateway/health").GetAwaiter().GetResult()
            break
        } catch {
            if ($attempt -eq 10) { throw }
            Start-Sleep -Seconds 1
        }
    }
    $healthBody = $healthRes.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Output "Health: Status $([int]$healthRes.StatusCode) -> $healthBody"
    if ($healthRes.StatusCode -ne [System.Net.HttpStatusCode]::OK) {
        throw "Gateway health check failed: $([int]$healthRes.StatusCode)"
    }

    Write-Output "2. Checking Gateway Version Endpoint ($GatewayUrl/_gateway/version)..."
    $versionRes = $client.GetAsync("$GatewayUrl/_gateway/version").GetAwaiter().GetResult()
    $versionBody = $versionRes.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    Write-Output "Active Version: $versionBody"
    if ($versionRes.StatusCode -ne [System.Net.HttpStatusCode]::OK -or [string]::IsNullOrWhiteSpace($versionBody)) {
        throw "Gateway version check failed"
    }

    Write-Output "3. Sending sample traffic to generate metrics..."
    for ($i = 0; $i -lt 5; $i++) {
        $sampleRes = $client.GetAsync("$GatewayUrl/vets").GetAwaiter().GetResult()
    }

    Write-Output "4. Checking Gateway Prometheus Metrics ($GatewayUrl/_gateway/metrics)..."
    $metricsRes = $client.GetAsync("$GatewayUrl/_gateway/metrics").GetAwaiter().GetResult()
    $metricsBody = $metricsRes.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if ($metricsRes.StatusCode -ne [System.Net.HttpStatusCode]::OK) {
        throw "Metrics endpoint failed: $([int]$metricsRes.StatusCode)"
    }

    $requiredMetrics = @(
        'gateway_requests_total',
        'gateway_request_duration_seconds',
        'gateway_in_flight_requests',
        'gateway_config_version'
    )
    foreach ($m in $requiredMetrics) {
        if (-not $metricsBody.Contains($m)) {
            throw "Expected metric '$m' not found in Prometheus export."
        }
        Write-Output "  [OK] Found metric: $m"
    }

    Write-Output "5. Testing Prometheus scraping (if Prometheus is running)..."
    try {
        $promHealth = $client.GetAsync("$PrometheusUrl/-/healthy").GetAwaiter().GetResult()
        Write-Output "Prometheus Status: $([int]$promHealth.StatusCode)"
    } catch {
        Write-Output "Prometheus container not running locally; skipped external scrape check."
    }

    Write-Output "Observability verification PASS"
} finally {
    $client.Dispose()
}
