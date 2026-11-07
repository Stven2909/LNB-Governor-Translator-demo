# Runner E2E: agente E3.2 -> WS de boletos de Carlos.
#
# Requisitos:
#   - WS de Carlos escuchando (por defecto http://localhost:8085), en modo semilla
#     (APP_SYBASE_TICKET_ENABLED=false, APP_SYBASE_TICKET_SEED_ENABLED=true).
#   - Agente E3.2 escuchando (por defecto http://localhost:8080) con
#     APP_TICKET_AGENT_MODE=HTTP y APP_TICKET_AGENT_BASE_URL apuntando al WS.
#
# Uso:
#   .\verify-agent-e2e.ps1 -WsUrl http://localhost:8085 -AgentUrl http://localhost:8080 -OutFile e2e-trace.txt
#
# Para una corrida Sybase real:
#   .\verify-agent-e2e.ps1 ... -ExpectedProvenance REAL -ExpectedSource SYBASE
#
# Si se pasa -WsLogPath, el runner exige DOS lineas "Procesando consulta de boleto"
# por correlacion: una de su llamada directa al WS y otra del agente. Sin ese log,
# la traza por si sola no distingue el modo HTTP del modo FIXTURE.
#
# Deja la traza cruda (status, cabeceras y cuerpo) en -OutFile y devuelve exit 0 solo
# si los 3 casos canonicos, el eco de correlacion y las cabeceras coinciden.
# Compatible con Windows PowerShell 5.1 y PowerShell 7+.

param(
    [string]$WsUrl    = "http://localhost:8085",
    [string]$AgentUrl = "http://localhost:8080",
    [string]$OutFile  = "e2e-trace.txt",
    [string]$ExpectedSource     = "SEED",
    [string]$ExpectedProvenance = "SYNTHETIC",
    [string]$WsLogPath          = ""
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

function Write-Headers {
    param($Response, [string]$Prefix)
    if ($null -eq $Response -or $null -eq $Response.Headers) { return }
    foreach ($entry in $Response.Headers.GetEnumerator()) {
        if ($entry -is [System.Collections.DictionaryEntry]) {
            Write-Trace ("{0}{1}: {2}" -f $Prefix, $entry.Key, [string]$entry.Value)
        } else {
            $joined = [string]::Join(", ", @($entry.Value))
            Write-Trace ("{0}{1}: {2}" -f $Prefix, $entry.Key, $joined)
        }
    }
}

function Post-Json {
    param([string]$Url, [string]$Body, [string]$CorrelationId)
    $headers = @{
        "X-Correlation-Id" = $CorrelationId
        "X-Request-Id"     = $CorrelationId
    }
    return Invoke-WebRequest -Uri $Url -Method POST `
        -ContentType "application/json" `
        -Headers $headers -Body $Body -UseBasicParsing
}

$wsLookup  = "$WsUrl/api/v1/tickets/lookup"
$agentPath = "$AgentUrl/api/v1/agent/ticket-query"

"" | Out-File -FilePath $OutFile -Encoding utf8
Write-Trace "=== E2E agente E3.2 -> WS de boletos ==="
Write-Trace ("fecha={0}" -f (Get-Date -Format o))
Write-Trace ("ws={0} agente={1}" -f $WsUrl, $AgentUrl)
Write-Trace ("host={0} powershell={1}" -f $env:COMPUTERNAME, $PSVersionTable.PSVersion)

$fail = 0

# El endpoint de estado es opcional: su ausencia no invalida los 3 casos.
try {
    $status = Invoke-WebRequest -Uri "$WsUrl/api/v1/tickets/status" -UseBasicParsing
    Write-Trace ("--- WS /status {0}" -f $status.StatusCode)
    Write-Headers -Response $status -Prefix "header "
    Write-Trace ([string]$status.Content)
} catch {
    Write-Trace ("WARN /status no disponible: {0}" -f $_.Exception.Message)
}

$cases = @(
    @{ Id = "0012345678"; Result = "FOUND";       WsFound = $true;  WsAwarded = $true  },
    @{ Id = "0011111111"; Result = "NOT_AWARDED"; WsFound = $true;  WsAwarded = $false },
    @{ Id = "9999999999"; Result = "NOT_FOUND";   WsFound = $false; WsAwarded = $false }
)

foreach ($case in $cases) {
    $ticketNumber = $case.Id
    $correlationId = "corr-e2e-$ticketNumber"

    $wsBody = @{
        lotteryCode  = "LNB"
        drawNumber   = "2026-10-15"
        ticketNumber = $ticketNumber
    } | ConvertTo-Json -Depth 5

    try {
        $ws = Post-Json -Url $wsLookup -Body $wsBody -CorrelationId $correlationId
    } catch {
        Write-Trace ("FAIL WS {0}: excepcion {1}" -f $ticketNumber, $_.Exception.Message)
        $fail++
        continue
    }

    Write-Trace ("--- WS {0} {1}" -f $ticketNumber, $ws.StatusCode)
    Write-Headers -Response $ws -Prefix "header "
    Write-Trace ([string]$ws.Content)

    $wsJson = $null
    try { $wsJson = $ws.Content | ConvertFrom-Json } catch { }

    if ($ws.StatusCode -ne 200) {
        Write-Trace ("FAIL WS {0}: status={1}" -f $ticketNumber, $ws.StatusCode)
        $fail++
    }
    if ($null -eq $wsJson) {
        Write-Trace ("FAIL WS {0}: cuerpo no es JSON" -f $ticketNumber)
        $fail++
    } else {
        if ($wsJson.found -ne $case.WsFound) {
            Write-Trace ("FAIL WS {0}: found={1} esperado={2}" -f $ticketNumber, $wsJson.found, $case.WsFound)
            $fail++
        }
        if ($wsJson.awarded -ne $case.WsAwarded) {
            Write-Trace ("FAIL WS {0}: awarded={1} esperado={2}" -f $ticketNumber, $wsJson.awarded, $case.WsAwarded)
            $fail++
        }
        if ($wsJson.provenance -ne $ExpectedProvenance -or $wsJson.source -ne $ExpectedSource) {
            Write-Trace ("FAIL WS {0}: provenance/source={1}/{2} (esperado {3}/{4})" -f `
                $ticketNumber, $wsJson.provenance, $wsJson.source, $ExpectedProvenance, $ExpectedSource)
            $fail++
        }
    }
    if ((Get-HeaderValue -Response $ws -Name "X-Correlation-Id") -ne $correlationId) {
        Write-Trace ("FAIL WS {0}: no ecoa X-Correlation-Id" -f $ticketNumber)
        $fail++
    }
    if ((Get-HeaderValue -Response $ws -Name "X-Request-Id") -ne $correlationId) {
        Write-Trace ("FAIL WS {0}: no ecoa X-Request-Id" -f $ticketNumber)
        $fail++
    }

    $agentBody = @{
        ticket = @{
            lotteryCode  = "LNB"
            drawNumber   = "2026-10-15"
            ticketNumber = $ticketNumber
        }
    } | ConvertTo-Json -Depth 5

    try {
        $agent = Post-Json -Url $agentPath -Body $agentBody -CorrelationId $correlationId
    } catch {
        Write-Trace ("FAIL AGENTE {0}: excepcion {1}" -f $ticketNumber, $_.Exception.Message)
        $fail++
        continue
    }

    Write-Trace ("--- AGENTE {0} {1}" -f $ticketNumber, $agent.StatusCode)
    Write-Headers -Response $agent -Prefix "header "
    Write-Trace ([string]$agent.Content)

    $agentJson = $null
    try { $agentJson = $agent.Content | ConvertFrom-Json } catch { }

    if ($agent.StatusCode -ne 200) {
        Write-Trace ("FAIL AGENTE {0}: status={1}" -f $ticketNumber, $agent.StatusCode)
        $fail++
    }
    if ($null -eq $agentJson) {
        Write-Trace ("FAIL AGENTE {0}: cuerpo no es JSON" -f $ticketNumber)
        $fail++
    } else {
        if ($agentJson.result -ne $case.Result) {
            Write-Trace ("FAIL AGENTE {0}: result={1} esperado={2}" -f $ticketNumber, $agentJson.result, $case.Result)
            $fail++
        }
        if ($agentJson.provenance -ne $ExpectedProvenance) {
            Write-Trace ("FAIL AGENTE {0}: provenance={1} esperado={2}" -f $ticketNumber, $agentJson.provenance, $ExpectedProvenance)
            $fail++
        }
        if ($null -eq $agentJson.source -or [string]$agentJson.source -ne $ExpectedSource) {
            Write-Trace ("FAIL AGENTE {0}: source={1} esperado={2} (FIXTURE no declara source)" -f `
                $ticketNumber, $agentJson.source, $ExpectedSource)
            $fail++
        }
        if ($agentJson.traceId -ne $correlationId) {
            Write-Trace ("FAIL AGENTE {0}: traceId={1} esperado={2}" -f $ticketNumber, $agentJson.traceId, $correlationId)
            $fail++
        }
        if ($case.Result -eq "FOUND") {
            if ($null -eq $agentJson.prize -or [string]$agentJson.prize.amount -ne "50.00" -or
                [string]$agentJson.prize.currency -ne "USD" -or [string]$agentJson.prize.category -ne "CASH") {
                Write-Trace ("FAIL AGENTE {0}: premio inesperado" -f $ticketNumber)
                $fail++
            }
            if ([string]$agentJson.collectionStatus -ne "PENDING") {
                Write-Trace ("FAIL AGENTE {0}: collectionStatus={1} esperado=PENDING" -f $ticketNumber, $agentJson.collectionStatus)
                $fail++
            }
        }
        if ($case.Result -ne "FOUND" -and $null -ne $agentJson.prize) {
            Write-Trace ("FAIL AGENTE {0}: premio presente en {1}" -f $ticketNumber, $case.Result)
            $fail++
        }
        if ($case.Result -ne "FOUND") {
            # ConvertFrom-Json convierte "prize":null en $null y el chequeo anterior no lo ve.
            # Se inspecciona el JSON crudo para exigir que el campo NO exista.
            if ($agent.Content -match '"prize"\s*:') {
                Write-Trace ("FAIL AGENTE {0}: JSON crudo contiene prize en {1}" -f $ticketNumber, $case.Result)
                $fail++
            }
            if ($agent.Content -match '"collectionStatus"\s*:') {
                Write-Trace ("FAIL AGENTE {0}: JSON crudo contiene collectionStatus en {1}" -f $ticketNumber, $case.Result)
                $fail++
            }
        }
    }
    if ((Get-HeaderValue -Response $agent -Name "X-Correlation-Id") -ne $correlationId) {
        Write-Trace ("FAIL AGENTE {0}: no ecoa X-Correlation-Id" -f $ticketNumber)
        $fail++
    }
    if ((Get-HeaderValue -Response $agent -Name "X-Request-Id") -ne $correlationId) {
        Write-Trace ("FAIL AGENTE {0}: no ecoa X-Request-Id" -f $ticketNumber)
        $fail++
    }
}

# Prueba documental: si se entrega el log del WS, cada correlacion debe aparecer DOS
# veces (llamada directa del runner + llamada del agente). En FIXTURE solo aparece una.
if ([string]::IsNullOrWhiteSpace($WsLogPath)) {
    Write-Trace "WARN sin -WsLogPath no se puede probar que el agente llamo al WS; use la prueba negativa o pase el log"
} elseif (-not (Test-Path -LiteralPath $WsLogPath)) {
    Write-Trace ("FAIL WsLogPath no existe: {0}" -f $WsLogPath)
    $fail++
} else {
    $log = Get-Content -LiteralPath $WsLogPath -ErrorAction SilentlyContinue
    foreach ($case in $cases) {
        # Solo la linea "Procesando": el controller tambien loguea "Resultado" con el
        # mismo correlationId y contarla duplicaria el numero esperado.
        $needle = "correlationId=corr-e2e-$($case.Id)] Procesando consulta de boleto"
        $count = ($log | Select-String -SimpleMatch $needle | Measure-Object).Count
        if ($count -ne 2) {
            Write-Trace ("FAIL WS log {0}: {1} apariciones de {2} (esperadas 2: runner + agente)" -f `
                $case.Id, $count, $needle)
            $fail++
        }
    }
}

if ($fail -eq 0) {
    Write-Trace "=== E2E OK ==="
    exit 0
} else {
    Write-Trace ("=== E2E FAIL ({0} fallos) ===" -f $fail)
    exit 1
}
