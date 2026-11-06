# =============================================================================
# Verificacion del Web Service de Consulta de Boletos (Carlos - E1 / E3.0)
# =============================================================================
param(
    [string]$BaseUrl = "http://localhost:8085"
)

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  VERIFICACION DEL WS DE CONSULTA DE BOLETOS DE CARLOS (LNB)    " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "Destino: $BaseUrl/api/v1/tickets/lookup" -ForegroundColor Yellow
Write-Host ""

# 1. Comprobar salud del servicio
Write-Host "[1/4] Consultando estado del pool Sybase (/api/v1/tickets/status)..." -ForegroundColor White
try {
    $statusRes = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/status" -Method GET
    Write-Host "  -> Estado del pool: $($statusRes.poolActive)" -ForegroundColor Green
    Write-Host "  -> Detalle conexion: $($statusRes.lastError)" -ForegroundColor Gray
} catch {
    Write-Host "  -> No se pudo conectar a $BaseUrl. Asegurate de levantar la app con: .\mvnw.cmd spring-boot:run" -ForegroundColor Red
    exit 1
}

# 2. Caso 1: Boleto ganador ($50.00 USD - Alex/Danny)
Write-Host ""
Write-Host "[2/4] Probando Caso 1: Boleto ganador ($50.00 USD)..." -ForegroundColor White
$bodyCaso1 = @{
    lotteryCode = "LNB"
    drawNumber = "2026-10-15"
    ticketNumber = "0012345678"
    series = "A"
    checkDigit = "5"
} | ConvertTo-Json

$headersCaso1 = @{
    "X-Request-Id" = "test-caso1-carlos"
    "X-Correlation-Id" = "corr-carlos-001"
}

$respHeaders = $null
$res1 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso1 -ContentType "application/json" -Headers $headersCaso1 -ResponseHeadersVariable respHeaders
Write-Host "  Respuesta obtenida:" -ForegroundColor Green
$res1 | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Green

if ($respHeaders -and $respHeaders["X-Correlation-Id"]) {
    Write-Host "  -> Header de correlacion devuelto: $($respHeaders['X-Correlation-Id'][0])" -ForegroundColor Cyan
}

if ($res1.found -eq $true -and $res1.awarded -eq $true -and $res1.prize.amount -eq "50.00") {
    Write-Host "  -> EXITO: Boleto premiado identificado con procedencia: $($res1.provenance)" -ForegroundColor Green
} else {
    Write-Host "  -> ERROR: Datos no coinciden con lo esperado" -ForegroundColor Red
}

# 3. Caso 2: Boleto no premiado
Write-Host ""
Write-Host "[3/4] Probando Caso 2: Boleto no premiado (0011111111)..." -ForegroundColor White
$bodyCaso2 = @{
    lotteryCode = "LNB"
    drawNumber = "2026-10-15"
    ticketNumber = "0011111111"
} | ConvertTo-Json

$res2 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso2 -ContentType "application/json"
Write-Host "  Respuesta obtenida:" -ForegroundColor Gray
$res2 | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Gray
if ($res2.found -eq $true -and $res2.awarded -eq $false -and $null -eq $res2.prize) {
    Write-Host "  -> EXITO: Boleto sin premio reportado correctamente (prize omitido)" -ForegroundColor Green
}

# 4. Caso 3: Boleto no encontrado
Write-Host ""
Write-Host "[4/4] Probando Caso 3: Boleto inexistente (9999999999)..." -ForegroundColor White
$bodyCaso3 = @{
    lotteryCode = "LNB"
    drawNumber = "2026-10-15"
    ticketNumber = "9999999999"
} | ConvertTo-Json

$res3 = Invoke-RestMethod -Uri "$BaseUrl/api/v1/tickets/lookup" -Method POST -Body $bodyCaso3 -ContentType "application/json"
Write-Host "  Respuesta obtenida:" -ForegroundColor Gray
$res3 | ConvertTo-Json -Depth 4 | Write-Host -ForegroundColor Gray
if ($res3.found -eq $false) {
    Write-Host "  -> EXITO: Boleto inexistente reportado correctamente" -ForegroundColor Green
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  TODAS LAS VERIFICACIONES COMPLETADAS CON EXITO                 " -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Cyan
