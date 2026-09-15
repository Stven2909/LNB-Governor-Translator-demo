# Demo live: detiene el jar que arrancó demo-live-start.ps1.
# Prioriza el proceso que escucha en :8080 (el server real); el PID guardado es un fallback.

$ErrorActionPreference = 'Continue'

$pidF = Join-Path 'target\demo' 'app.pid'

function Get-WorkerListener {
    $conn = Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue
    if ($conn) { return $conn | Select-Object -First 1 -ExpandProperty OwningProcess }
    return $null
}

$listener = Get-WorkerListener
if ($listener) {
    $p = Get-Process -Id $listener -ErrorAction SilentlyContinue
    if ($p) {
        Stop-Process -Id $listener -Force
        Write-Host "Detenido worker de :8080 (PID=$listener)"
    }
} elseif (Test-Path $pidF) {
    $saved = [int](Get-Content $pidF)
    $p = Get-Process -Id $saved -ErrorAction SilentlyContinue
    if ($p) {
        Stop-Process -Id $saved -Force
        Write-Host "Detenido java PID=$saved (fallback pidfile)"
    } else {
        Write-Host "java PID=$saved no estaba corriendo."
    }
} else {
    Write-Host "Nada que detener: :8080 libre y sin pidfile."
}

Remove-Item $pidF -ErrorAction SilentlyContinue