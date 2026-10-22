param(
    [string]$ControlUrl = 'http://localhost:9090',
    [string]$GatewayUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'
$token = if ($env:CONTROL_PLANE_TOKEN) { $env:CONTROL_PLANE_TOKEN } else { 'local-demo-token' }
$headers = @{ Authorization = "Bearer $token" }
$initial = Invoke-RestMethod -Uri "$ControlUrl/admin/config/active" -Headers $headers
$published = $null

function Wait-GatewayVersion([long]$expected) {
    for ($attempt = 0; $attempt -lt 20; $attempt++) {
        $current = [long](Invoke-WebRequest -UseBasicParsing -Uri "$GatewayUrl/_gateway/version").Content
        if ($current -eq $expected) { return }
        Start-Sleep -Seconds 1
    }
    throw "Gateway did not apply configuration version $expected"
}

try {
    $routes = @($initial.routes | Where-Object { $_.id -ne 'vets' })
    if ($routes.Count -eq $initial.routes.Count) { throw 'The active configuration has no vets route.' }
    if ($routes.Count -lt 1) { throw 'At least one route must remain.' }
    $body = @{
        expectedVersion = [long]$initial.version
        responseTimeoutSeconds = [long]$initial.responseTimeoutSeconds
        routes = $routes
    } | ConvertTo-Json -Depth 20
    $published = Invoke-RestMethod -Method Post -Uri "$ControlUrl/admin/config/versions" `
        -Headers $headers -ContentType 'application/json' -Body $body
    Wait-GatewayVersion ([long]$published.version)

    try {
        Invoke-WebRequest -UseBasicParsing -Uri "$GatewayUrl/vets" | Out-Null
        throw 'Expected /vets to return 404 after route removal.'
    } catch [System.Net.WebException] {
        if ([int]$_.Exception.Response.StatusCode -ne 404) { throw }
    }
    Write-Output "Published version $($published.version): /vets -> 404 without gateway restart"
} finally {
    if ($null -ne $published) {
        $active = Invoke-RestMethod -Uri "$ControlUrl/admin/config/active" -Headers $headers
        if ([long]$active.version -eq [long]$published.version) {
            $rollbackBody = @{ expectedVersion = [long]$active.version } | ConvertTo-Json
            $restored = Invoke-RestMethod -Method Post `
                -Uri "$ControlUrl/admin/config/rollback/$($initial.version)" `
                -Headers $headers -ContentType 'application/json' -Body $rollbackBody
            Wait-GatewayVersion ([long]$restored.version)
            $response = Invoke-WebRequest -UseBasicParsing -Uri "$GatewayUrl/vets"
            if ([int]$response.StatusCode -ne 200) { throw 'Rollback did not restore /vets.' }
            Write-Output "Rolled back as version $($restored.version): /vets -> 200"
        } else {
            Write-Warning 'Active version changed concurrently; automatic rollback was skipped.'
        }
    }
}

Write-Output 'M5 control plane verification PASS'
