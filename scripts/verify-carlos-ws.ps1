# =============================================================================
# Verificación Estricta del Web Service de Consulta de Boletos (Carlos - E1 / E3.0)
# =============================================================================
param(
    [string]$BaseUrl = "http://localhost:8085",
    [switch]$StrictMode = $false,
    [string]$RealTicket = $(if ($env:SYBASE_REAL_TICKET) { $env:SYBASE_REAL_TICKET } else { "0012345678" })
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  VERIFICACION DEL WS DE CONSULTA DE BOLETOS DE CARLOS (LNB)    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Destino: $BaseUrl/api/v1/tickets/lookup" -ForegroundColor Yellow
if ($StrictMode) {
    Write-Host "MODO: ESTRICTO REAL (Exige poolActive=true, provenance=REAL, source=SYBASE, databaseSource='Sybase ASE LNB DEV')" -ForegroundColor Magenta
    Write-Host "Boleto objetivo: $RealTicket" -ForegroundColor Magenta
} else {
    Write-Host "MODO: DESARROLLO (Acepta fixture de pruebas si no hay VPN activa)" -ForegroundColor Gray
}
Write-Host ""

# 1. Comprobar salud del servicio (/api/v1/tickets/status)
Write-Host "[1/4] Consultando estado del pool Sybase (/api/v1/tickets/status)..." -ForegroundColor White
try {
    $statusRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/status" -Method GET
    Write-Host "  -> Estado del pool: $($statusRes.poolActive)" -ForegroundColor $(if ($statusRes.poolActive) { "Green" } else { "Yellow" })
    Write-Host "  -> Seed habilitada: $($statusRes.seedEnabled)" -ForegroundColor Gray
    Write-Host "  -> Detalle conexion: $($statusRes.lastError)" -ForegroundColor Gray

    if ($StrictMode) {
        if (-not $statusRes.poolActive) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: El pool hacia Sybase no esta activo (poolActive=$($statusRes.poolActive)). Verifique VPN." -ForegroundColor Red
            exit 1
        }
        if ($null -ne $statusRes.lastError) {
            Write-Host "  [FAIL CRITICO] MODO ESTRICTO: lastError debe ser null en corrida exitosa. Recibido: $($statusRes.lastError)" -ForegroundColor Red
            exit 1
        }
    }
} catch {
    Write-Host "  -> No se pudo conectar a $BaseUrl. Asegurate de levantar la app con perfil carlos: .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos" -ForegroundColor Red
    exit 1
}

# 2. Caso 1: Boleto ganador
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

# Validar campos de negocio
if ($res1.found -ne $true -or $res1.awarded -ne $true -or $null -eq $res1.prize -or $null -eq $res1.prize.amount) {
    Write-Host "  [FAIL CRITICO] Los datos del premio no coinciden con lo esperado (found=true, awarded=true, prize.amount presente)." -ForegroundColor Red
    exit 1
}

# Validar procedencia, databaseSource y collectionStatus según modo
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
Write-Host "  -> EXITO Caso 1: Boleto premiado verificado con procedencia '$($res1.provenance)', source '$($res1.source)', status '$($res1.collectionStatus)' y databaseSource '$($res1.databaseSource)'" -ForegroundColor Green

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
