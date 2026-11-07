# Prueba negativa de fail-closed: agente E3.2 con el WS de boletos CAIDO.
#
# Objetivo: distinguir el modo HTTP del modo FIXTURE. Con el WS caido:
#   - Agente HTTP  -> 502 WS_ERROR (o 504 WS_TIMEOUT si el puerto queda colgado).
#   - Agente FIXTURE -> 200 con el fixture; NO esta llamando al WS.
#
# Requisito: detener el WS (Ctrl+C) ANTES de ejecutar este script. El agente debe
# seguir levantado y apuntando al WS por APP_TICKET_AGENT_BASE_URL.
#
# Uso:
#   .\verify-agent-failclosed.ps1 -AgentUrl http://localhost:8081 -OutFile agent-failclosed.txt
#
# Devuelve exit 0 solo si el agente responde 5xx con correlacion ecoada.

param(
    [string]$AgentUrl      = "http://localhost:8080",
    [string]$OutFile       = "agent-failclosed.txt",
    [string]$CorrelationId = "corr-neg-$(Get-Date -Format 'yyyyMMddHHmmss')"
)

$ErrorActionPreference = "Stop"

function Write-Trace {
    param([string]$Text)
    $Text | Out-File -FilePath $OutFile -Append -Encoding utf8
    Write-Host $Text
}

function Get-HeaderValue {
    param($Response, [string]$Name)
    if ($null -eq $Response -or $null -eq $Response.Headers) { return "" }
    $value = $Response.Headers[$Name]
    if ($null -eq $value) { return "" }
    if (($value -is [System.Collections.IEnumerable]) -and -not ($value -is [string])) {
        $first = @($value)[0]
        if ($null -eq $first) { return "" }
        return [string]$first
    }
    return [string]$value
}

function Get-ErrorBody {
    param($ErrorRecord)
    $message = $ErrorRecord.ErrorDetails.Message
    if (-not [string]::IsNullOrWhiteSpace($message)) { return [string]$message }

    $resp = $ErrorRecord.Exception.Response
    if ($null -eq $resp) { return "" }
    try {
        if ($resp -is [System.Net.HttpWebResponse]) {
            $stream = $resp.GetResponseStream()
            if ($null -ne $stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                return $reader.ReadToEnd()
            }
        }
    } catch { }
    try {
        if ($null -ne $resp.Content) {
            return [string]$resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        }
    } catch { }
    return ""
}

"" | Out-File -FilePath $OutFile -Encoding utf8
Write-Trace "=== Agente E3.2 fail-closed con WS caido ==="
Write-Trace ("fecha={0}" -f (Get-Date -Format o))
Write-Trace ("agente={0} correlacion={1}" -f $AgentUrl, $CorrelationId)
Write-Trace "Requisito: el WS debe estar DETENIDO. Si el agente responde 200, esta en FIXTURE."

$body = '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}'
$headers = @{
    "X-Correlation-Id" = $CorrelationId
    "X-Request-Id"     = $CorrelationId
}

$fail = 0
try {
    $response = Invoke-WebRequest -Uri "$AgentUrl/api/v1/agent/ticket-query" -Method POST `
        -ContentType "application/json" -Headers $headers -Body $body -UseBasicParsing

    Write-Trace ("--- AGENTE {0}" -f $response.StatusCode)
    Write-Trace ([string]$response.Content)
    Write-Trace "FAIL: el agente respondio 200 con el WS caido; no esta llamando al WS (modo FIXTURE?)"
    $fail++
} catch {
    $resp = $_.Exception.Response
    if ($null -eq $resp) {
        Write-Trace ("FAIL: el agente no respondio ({0})" -f $_.Exception.Message)
        $fail++
    } else {
        $status = [int]$resp.StatusCode
        $content = Get-ErrorBody -ErrorRecord $_
        Write-Trace ("--- AGENTE {0}" -f $status)
        Write-Trace $content

        if ($status -notin @(502, 503, 504)) {
            Write-Trace ("FAIL: status={0}; se esperaba 502/503/504 con el WS caido" -f $status)
            $fail++
        }

        $code = $null
        try { $code = ($content | ConvertFrom-Json).code } catch { }
        if ($code -notin @("WS_ERROR", "DATABASE_UNAVAILABLE", "WS_TIMEOUT")) {
            Write-Trace ("FAIL: code={0}; se esperaba WS_ERROR/DATABASE_UNAVAILABLE/WS_TIMEOUT" -f $code)
            $fail++
        }

        if ((Get-HeaderValue -Response $resp -Name "X-Correlation-Id") -ne $CorrelationId) {
            Write-Trace "FAIL: no ecoa X-Correlation-Id en el error"
            $fail++
        }
        if ((Get-HeaderValue -Response $resp -Name "X-Request-Id") -ne $CorrelationId) {
            Write-Trace "FAIL: no ecoa X-Request-Id en el error"
            $fail++
        }
    }
}

if ($fail -eq 0) {
    Write-Trace "=== FAIL-CLOSED OK ==="
    exit 0
} else {
    Write-Trace ("=== FAIL-CLOSED FAIL ({0} fallos) ===" -f $fail)
    exit 1
}
