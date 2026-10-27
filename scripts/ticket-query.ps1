# Runner HTTP del agente de boleto (5 casos: FOUND, NOT_AWARDED, NOT_FOUND, 400, 504).
# Usa datos SINTETICOS. En modo HTTP puede apuntar al MOCK local
# (scripts/mock-ticket-ws.ps1); el WS real de Carlos aun NO esta disponible.
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Path = "/api/v1/agent/ticket-query"
)

$uri = $BaseUrl + $Path

function Write-Response {
    param([string]$Id, [int]$Status, [string]$Content)
    Write-Host "[$Id] HTTP $Status"
    Write-Host $Content
    Write-Host ""
}

function Invoke-TicketCase {
    param([string]$Id, [string]$Body)

    try {
        $response = Invoke-WebRequest -Uri $uri -Method Post `
            -Headers @{ "X-Request-Id" = $Id } `
            -ContentType "application/json" `
            -Body $Body `
            -UseBasicParsing
        Write-Response -Id $Id -Status ([int]$response.StatusCode) -Content $response.Content
    }
    catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp -and $null -ne $_.ErrorDetails) {
            Write-Response -Id $Id -Status 0 -Content $_.ErrorDetails.Message
            return
        }
        if ($null -eq $resp) {
            Write-Host "[$Id] ERROR $($_.Exception.Message)"
            Write-Host ""
            return
        }

        $status = [int]$resp.StatusCode
        if ($null -ne $resp.PSObject.Methods["GetResponseStream"]) {
            $reader = [System.IO.StreamReader]::new($resp.GetResponseStream())
            $content = $reader.ReadToEnd()
        }
        elseif ($null -ne $resp.Content -and $null -ne $resp.Content.PSObject.Methods["ReadAsStringAsync"]) {
            $content = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
        else {
            $content = [string]$resp
        }
        Write-Response -Id $Id -Status $status -Content $content
    }
}

Invoke-TicketCase -Id "smoke-001" -Body '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678","series":"A","checkDigit":"5"}}'
Invoke-TicketCase -Id "smoke-002" -Body '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0011111111"}}'
Invoke-TicketCase -Id "smoke-003" -Body '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"9999999999"}}'
Invoke-TicketCase -Id "smoke-004-invalid" -Body '{"ticket":{"drawNumber":"2026-10-15","ticketNumber":"0012345678"}}'
Invoke-TicketCase -Id "smoke-005-timeout" -Body '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"TIMEOUT"}}'
