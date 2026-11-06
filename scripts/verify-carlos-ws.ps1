# =============================================================================
# Verificación Estricta del Web Service de Consulta de Boletos (Carlos - E1 / E3.0)
# =============================================================================
param(
    [string]$BaseUrl = "http://localhost:8085",
    [switch]$StrictMode = $false,
    [string]$RealTicket = $(if ($env:SYBASE_REAL_TICKET) { $env:SYBASE_REAL_TICKET } else { "" }),
    [string]$RealLottery = $(if ($env:SYBASE_REAL_LOTTERY) { $env:SYBASE_REAL_LOTTERY } else { "" }),
    [string]$RealDraw = $(if ($env:SYBASE_REAL_DRAW) { $env:SYBASE_REAL_DRAW } else { "" }),
    [string]$ExpectedAmount = $(if ($env:SYBASE_REAL_AMOUNT) { $env:SYBASE_REAL_AMOUNT } else { "50.00" }),
    [string]$ExpectedCurrency = $(if ($env:SYBASE_REAL_CURRENCY) { $env:SYBASE_REAL_CURRENCY } else { "USD" }),
    [switch]$SkipContractCases = $false,
    [string]$RawOutputFile = ""
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  VERIFICACION DEL WS DE CONSULTA DE BOLETOS DE CARLOS (LNB)    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Destino: $BaseUrl/api/v1/tickets/lookup" -ForegroundColor Yellow

if ($StrictMode) {
    if ([string]::IsNullOrWhiteSpace($RealTicket) -or [string]::IsNullOrWhiteSpace($RealLottery) -or [string]::IsNullOrWhiteSpace($RealDraw)) {
        Write-Host "  [FAIL CRITICO] MODO ESTRICTO: Debe especificar obligatoriamente la clave completa de 3 campos confirmados:" -ForegroundColor Red
        Write-Host "    -RealTicket <numero>  (o variable SYBASE_REAL_TICKET)" -ForegroundColor Red
        Write-Host "    -RealLottery <codigo> (o variable SYBASE_REAL_LOTTERY)" -ForegroundColor Red
        Write-Host "    -RealDraw <sorteo>    (o variable SYBASE_REAL_DRAW)" -ForegroundColor Red
        exit 1
    }
    Write-Host "MODO: ESTRICTO REAL (Exige poolActive=true, seedEnabled=false, lastError=null, provenance=REAL, source=SYBASE, databaseSource='Sybase ASE LNB DEV')" -ForegroundColor Magenta
    Write-Host "Clave real objetivo: Lottery=$RealLottery, Draw=$RealDraw, Ticket=$RealTicket" -ForegroundColor Magenta
    Write-Host "Premio esperado: $ExpectedAmount $ExpectedCurrency" -ForegroundColor Magenta
} else {
    if ([string]::IsNullOrWhiteSpace($RealTicket)) { $RealTicket = "0012345678" }
    if ([string]::IsNullOrWhiteSpace($RealLottery)) { $RealLottery = "LNB" }
    if ([string]::IsNullOrWhiteSpace($RealDraw)) { $RealDraw = "2026-10-15" }
    Write-Host "MODO: DESARROLLO LOCAL (Clave objetivo: Lottery=$RealLottery, Draw=$RealDraw, Ticket=$RealTicket)" -ForegroundColor Gray
}
Write-Host ""

# 1. Comprobar salud del servicio (/api/v1/tickets/status)
Write-Host "[1/4] Consultando estado del pool Sybase (/api/v1/tickets/status)..." -ForegroundColor White
try {
    $statusRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/status" -Method GET
    Write-Host "  -> Estado del pool: $($statusRes.poolActive)" -ForegroundColor $(if ($statusRes.poolActive) { "Green" } else { "Yellow" })
    Write-Host "  -> Origen de base de datos: $($statusRes.databaseSource)" -ForegroundColor Gray
    Write-Host "  -> Seed habilitada: $($statusRes.seedEnabled)" -ForegroundColor Gray
    Write-Host "  -> Error pool (lastError): $($statusRes.lastError)" -ForegroundColor Gray
    Write-Host "  -> Error consulta (lastQueryError): $($statusRes.lastQueryError)" -ForegroundColor Gray

    if ($StrictMode) {
        if ($statusRes.seedEnabled -eq $true) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: seedEnabled debe ser false en una corrida real contra Sybase. Recibido: seedEnabled=true" -ForegroundColor Red
            exit 1
        }
        if (-not $statusRes.poolActive) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: El pool hacia Sybase no esta activo (poolActive=$($statusRes.poolActive)). Verifique VPN y credenciales." -ForegroundColor Red
            exit 1
        }
        if ($null -ne $statusRes.lastError) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: lastError debe ser null en corrida exitosa. Recibido: $($statusRes.lastError)" -ForegroundColor Red
            exit 1
        }
        if ($null -ne $statusRes.lastQueryError) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: lastQueryError debe ser null en corrida exitosa. Recibido: $($statusRes.lastQueryError)" -ForegroundColor Red
            exit 1
        }
    } else {
        if ((-not $statusRes.poolActive) -and (-not $statusRes.seedEnabled)) {
            Write-Host ""
            Write-Host "  [ESTADO FAIL-CLOSED CONFIRMADO] El servicio opera de forma segura en fail-closed (poolActive=false y seedEnabled=false)." -ForegroundColor Yellow
            Write-Host "  Para verificar resolucion de boletos:" -ForegroundColor Yellow
            Write-Host "    A) Entorno offline simulado: levantar con `$env:APP_SYBASE_TICKET_SEED_ENABLED='true'" -ForegroundColor Cyan
            Write-Host "    B) Corrida real con VPN: .\scripts\verify-carlos-ws.ps1 -StrictMode -RealTicket <num> -RealLottery <cod> -RealDraw <sorteo>" -ForegroundColor Cyan
            Write-Host ""
            Write-Host "=================================================================" -ForegroundColor Cyan
            Write-Host "  VERIFICACION DE FAIL-CLOSED COMPLETADA EXITOSAMENTE (EXIT CODE 0)" -ForegroundColor Green
            Write-Host "=================================================================" -ForegroundColor Cyan
            exit 0
        }
    }
} catch {
    Write-Host "  -> No se pudo conectar a $BaseUrl. Asegurate de levantar la app con perfil carlos: .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos" -ForegroundColor Red
    exit 1
}

# 2. Caso 1: Boleto ganador
Write-Host ""
Write-Host "[2/4] Probando Caso 1: Boleto ganador ($RealLottery / $RealDraw / $RealTicket)..." -ForegroundColor White
$bodyCaso1 = @{
    lotteryCode = $RealLottery
    drawNumber = $RealDraw
    ticketNumber = $RealTicket
} | ConvertTo-Json

$headersCaso1 = @{
    "X-Request-Id" = "test-caso1-carlos"
    "X-Correlation-Id" = "corr-carlos-001"
}

$respHeaders = $null
try {
    $webResp = Invoke-WebRequest -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso1 -ContentType "application/json" -Headers $headersCaso1 -UseBasicParsing
    $res1 = $webResp.Content | ConvertFrom-Json
    $respHeaders = $webResp.Headers
    Write-Host "  HTTP Status Code: $($webResp.StatusCode)" -ForegroundColor Green
    Write-Host "  Respuesta cruda (Content):" -ForegroundColor Green
    Write-Host "  $($webResp.Content)" -ForegroundColor Green
} catch {
    Write-Host "  [ERROR] Peticion fallida: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Validar encabezado de correlación
$corrHeader = if ($respHeaders["X-Correlation-Id"] -is [array]) { $respHeaders["X-Correlation-Id"][0] } else { $respHeaders["X-Correlation-Id"] }
if ($corrHeader -ne "corr-carlos-001") {
    Write-Host "  [FAIL CRITICO] No se devolvio el encabezado X-Correlation-Id correspondiente. Recibido: '$corrHeader'" -ForegroundColor Red
    exit 1
}
Write-Host "  -> Header de correlacion devuelto y verificado: $corrHeader" -ForegroundColor Cyan

# Guardar evidencia cruda si se especificó el parámetro
if (-not [string]::IsNullOrWhiteSpace($RawOutputFile)) {
    "=== EVIDENCIA CORRIDA WS CARLOS ===" | Out-File -FilePath $RawOutputFile -Encoding utf8
    "Fecha: $(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssZ')" | Out-File -FilePath $RawOutputFile -Append -Encoding utf8
    "StatusCode: $($webResp.StatusCode)" | Out-File -FilePath $RawOutputFile -Append -Encoding utf8
    "X-Correlation-Id: $corrHeader" | Out-File -FilePath $RawOutputFile -Append -Encoding utf8
    "X-Request-Id: $($respHeaders['X-Request-Id'])" | Out-File -FilePath $RawOutputFile -Append -Encoding utf8
    "RawContent: $($webResp.Content)" | Out-File -FilePath $RawOutputFile -Append -Encoding utf8
    Write-Host "  -> Evidencia cruda guardada exitosamente en: $RawOutputFile" -ForegroundColor Cyan
}

# Validar campos de negocio del Caso 1
if ($res1.found -ne $true -or $res1.awarded -ne $true -or $res1.ticketNumber -ne $RealTicket) {
    Write-Host "  [FAIL CRITICO] El boleto debe responder found=true, awarded=true y ticketNumber='$RealTicket'." -ForegroundColor Red
    exit 1
}
if ($null -eq $res1.prize -or $res1.prize.amount -ne $ExpectedAmount -or $res1.prize.currency -ne $ExpectedCurrency) {
    Write-Host "  [FAIL CRITICO] Los datos del premio no coinciden con lo esperado ($ExpectedAmount $ExpectedCurrency). Recibido: $($res1.prize.amount) $($res1.prize.currency)" -ForegroundColor Red
    exit 1
}

# Validar procedencia, databaseSource y collectionStatus en modo estricto
if ($StrictMode) {
    if ($res1.provenance -ne "REAL" -or $res1.source -ne "SYBASE") {
        Write-Host "  [FAIL CRITICO] MODO ESTRICTO: provenance debe ser REAL y source debe ser SYBASE. Recibido: provenance=$($res1.provenance), source=$($res1.source)" -ForegroundColor Red
        exit 1
    }
    if ($res1.databaseSource -ne "Sybase ASE LNB DEV") {
        Write-Host "  [FAIL CRITICO] MODO ESTRICTO: databaseSource debe ser 'Sybase ASE LNB DEV'. Recibido: '$($res1.databaseSource)'" -ForegroundColor Red
        exit 1
    }
    if ([string]::IsNullOrWhiteSpace($res1.collectionStatus)) {
        Write-Host "  [FAIL CRITICO] MODO ESTRICTO: collectionStatus no puede ser vacio o nulo en boleto real." -ForegroundColor Red
        exit 1
    }
}
Write-Host "  -> EXITO Caso 1: Boleto premiado verificado ($($res1.prize.amount) $($res1.prize.currency)) con procedencia '$($res1.provenance)', source '$($res1.source)', status '$($res1.collectionStatus)' y databaseSource '$($res1.databaseSource)'" -ForegroundColor Green

# 3. Casos de contrato (Casos 2 y 3)
if ($SkipContractCases -or $StrictMode) {
    Write-Host ""
    Write-Host "[3/4 y 4/4] Omitiendo casos de contrato fixtures (0011111111 y 9999999999) en corrida real. Cierre de E1 enfocado en boleto ganador real confirmado." -ForegroundColor Yellow
} else {
    # Caso 2: Boleto no premiado
    Write-Host ""
    Write-Host "[3/4] Probando Caso 2: Boleto no premiado (0011111111)..." -ForegroundColor White
    $bodyCaso2 = @{
        lotteryCode = "LNB"
        drawNumber = "2026-10-15"
        ticketNumber = "0011111111"
    } | ConvertTo-Json

    $res2 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso2 -ContentType "application/json"
    if ($res2.found -ne $true -or $res2.awarded -ne $false -or $null -ne $res2.prize) {
        Write-Host "  [FAIL CRITICO] Caso no premiado debe devolver found=true, awarded=false y prize omitido." -ForegroundColor Red
        exit 1
    }
    Write-Host "  -> EXITO Caso 2: Boleto sin premio reportado correctamente (prize omitido)" -ForegroundColor Green

    # Caso 3: Boleto no encontrado
    Write-Host ""
    Write-Host "[4/4] Probando Caso 3: Boleto inexistente (9999999999)..." -ForegroundColor White
    $bodyCaso3 = @{
        lotteryCode = "LNB"
        drawNumber = "2026-10-15"
        ticketNumber = "9999999999"
    } | ConvertTo-Json

    $res3 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso3 -ContentType "application/json"
    if ($res3.found -ne $false) {
        Write-Host "  [FAIL CRITICO] Caso inexistente debe devolver found=false." -ForegroundColor Red
        exit 1
    }
    Write-Host "  -> EXITO Caso 3: Boleto inexistente reportado correctamente" -ForegroundColor Green
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  TODAS LAS VALIDACIONES COMPLETADAS EXITOSAMENTE (EXIT CODE 0)  " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
exit 0
