# =============================================================================
# Verificación Estricta del Web Service de Consulta de Boletos (Carlos - E1 / E3.0)
# =============================================================================
param(
    [string]$BaseUrl = "http://localhost:8085",
    [switch]$StrictMode = $false,
    [string]$RealTicket = $(if ($env:SYBASE_REAL_TICKET) { $env:SYBASE_REAL_TICKET } else { "" })
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  VERIFICACION DEL WS DE CONSULTA DE BOLETOS DE CARLOS (LNB)    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Destino: $BaseUrl/api/v1/tickets/lookup" -ForegroundColor Yellow

if ($StrictMode) {
    if ([string]::IsNullOrWhiteSpace($RealTicket)) {
        Write-Host "  [FAIL CRITICO] MODO ESTRICTO: Debe especificar el boleto real confirmado mediante el parametro -RealTicket <numero> o la variable de entorno SYBASE_REAL_TICKET." -ForegroundColor Red
        exit 1
    }
    Write-Host "MODO: ESTRICTO REAL (Exige poolActive=true, lastError=null, provenance=REAL, source=SYBASE, databaseSource='Sybase ASE LNB DEV')" -ForegroundColor Magenta
    Write-Host "Boleto objetivo: $RealTicket" -ForegroundColor Magenta
} else {
    if ([string]::IsNullOrWhiteSpace($RealTicket)) {
        $RealTicket = "0012345678"
    }
    Write-Host "MODO: DESARROLLO LOCAL (Boleto objetivo: $RealTicket)" -ForegroundColor Gray
}
Write-Host ""

# 1. Comprobar salud del servicio (/api/v1/tickets/status)
Write-Host "[1/4] Consultando estado del pool Sybase (/api/v1/tickets/status)..." -ForegroundColor White
try {
    $statusRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/status" -Method GET
    Write-Host "  -> Estado del pool: $($statusRes.poolActive)" -ForegroundColor $(if ($statusRes.poolActive) { "Green" } else { "Yellow" })
    Write-Host "  -> Seed habilitada: $($statusRes.seedEnabled)" -ForegroundColor Gray
    Write-Host "  -> Error pool (lastError): $($statusRes.lastError)" -ForegroundColor Gray
    Write-Host "  -> Error consulta (lastQueryError): $($statusRes.lastQueryError)" -ForegroundColor Gray

    if ($StrictMode) {
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
            Write-Host "  [NOTA INFORMATIVA] El servicio opera en FAIL-CLOSED (poolActive=false y seedEnabled=false)." -ForegroundColor Yellow
            Write-Host "  Si desea probar con el fixture simulado local en desarrollo offline, levante el servicio con:" -ForegroundColor Yellow
            Write-Host "  `$env:APP_SYBASE_TICKET_SEED_ENABLED='true'; .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos" -ForegroundColor Cyan
            Write-Host "  O para corrida real con VPN: .\scripts\verify-carlos-ws.ps1 -StrictMode -RealTicket <numero>" -ForegroundColor Cyan
            Write-Host ""
        }
    }
} catch {
    Write-Host "  -> No se pudo conectar a $BaseUrl. Asegurate de levantar la app con perfil carlos: .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos" -ForegroundColor Red
    exit 1
}

# 2. Caso 1: Boleto ganador ($50.00 USD)
Write-Host ""
Write-Host "[2/4] Probando Caso 1: Boleto ganador ($RealTicket)..." -ForegroundColor White
$bodyCaso1 = @{
    lotteryCode = "LNB"
    drawNumber = "2026-10-15"
    ticketNumber = $RealTicket
    series = "A"
    checkDigit = "5"
} | ConvertTo-Json

$headersCaso1 = @{
    "X-Request-Id" = "test-caso1-carlos"
    "X-Correlation-Id" = "corr-carlos-001"
}

$respHeaders = $null
try {
    $res1 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso1 -ContentType "application/json" -Headers $headersCaso1 -ResponseHeadersVariable respHeaders
    Write-Host "  Respuesta obtenida:" -ForegroundColor Green
    $res1 | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green
} catch {
    Write-Host "  [ERROR] Peticion fallida: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# Validar encabezado de correlación
if (-not ($respHeaders -and $respHeaders["X-Correlation-Id"] -and $respHeaders["X-Correlation-Id"][0] -eq "corr-carlos-001")) {
    Write-Host "  [FAIL CRITICO] No se devolvio el encabezado X-Correlation-Id correspondiente." -ForegroundColor Red
    exit 1
}
Write-Host "  -> Header de correlacion devuelto y verificado: $($respHeaders['X-Correlation-Id'][0])" -ForegroundColor Cyan

# Validar campos de negocio del Caso 1
if ($res1.found -ne $true -or $res1.awarded -ne $true -or $res1.ticketNumber -ne $RealTicket) {
    Write-Host "  [FAIL CRITICO] El boleto debe responder found=true, awarded=true y ticketNumber='$RealTicket'." -ForegroundColor Red
    exit 1
}
if ($null -eq $res1.prize -or $res1.prize.amount -ne "50.00" -or $res1.prize.currency -ne "USD") {
    Write-Host "  [FAIL CRITICO] Los datos del premio no coinciden con el Caso 1 (prize.amount=50.00 USD)." -ForegroundColor Red
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

# 3. Caso 2: Boleto no premiado
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

# 4. Caso 3: Boleto no encontrado
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

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  TODAS LAS VALIDACIONES COMPLETADAS EXITOSAMENTE (EXIT CODE 0)  " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
exit 0
