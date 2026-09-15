# Demo live: POSTea los envelopes live-safe (target/demo/envelopes/live/*.json) contra el jar
# activo y registra el resultado HTTP de cada uno en target/demo/demo-live-run.json.

$ErrorActionPreference = 'Stop'

$dir   = 'target\demo'
$pidF  = Join-Path $dir 'app.pid'
$live  = Join-Path $dir 'envelopes\live'
$out   = Join-Path $dir 'demo-live-run.json'

if (!(Test-Path $pidF)) {
    Write-Error "No existe $pidF; ejecuta demo-live-start.ps1 primero."
    exit 1
}

$pidUsed = [int](Get-Content $pidF)
if (!(Get-Process -Id $pidUsed -ErrorAction SilentlyContinue)) {
    Write-Error "El proceso java $pidUsed no está corriendo."
    exit 1
}

$results = @()
foreach ($file in (Get-ChildItem -Path $live -Filter '*.json' | Sort-Object Name)) {
    $body = Get-Content -Raw $file.FullName
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/push' `
            -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 15 -UseBasicParsing
        $results += [PSCustomObject]@{
            file         = $file.Name
            http         = [int]$r.StatusCode
            body         = $r.Content
        }
    } catch {
        $code = $_.Exception.Response.StatusCode.value__
        $results += [PSCustomObject]@{
            file         = $file.Name
            http         = $code
            body         = "-"
        }
    }
}

$results | ConvertTo-Json | Set-Content $out -NoNewline
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host "Evidencia de corrida en vivo -> $out"