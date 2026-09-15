# Demo live: arranca el jar en target/worker-poc.jar y espera a que Tomcat esté listo.
# Guarda el PID en target/demo/app.pid para que demo-live-run y demo-live-stop lo usen.

$ErrorActionPreference = 'Stop'

$jar   = 'target\worker-poc-0.0.1-SNAPSHOT.jar'
$dir   = 'target\demo'
$log   = Join-Path $dir 'app-stdout.log'
$pidF  = Join-Path $dir 'app.pid'

if (!(Test-Path $jar)) {
    Write-Error "No existe $jar; ejecuta mvn -DskipTests package primero."
    exit 1
}

New-Item -ItemType Directory -Force $dir | Out-Null

$port = (Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue)
if ($port) {
    Write-Error "Puerto 8080 ocupado; detén otro proceso antes de continuar."
    exit 1
}

Write-Host "Arrancando worker-poc en :8080 ..."
$proc = Start-Process java -ArgumentList @(
    "-jar", $jar
) -WorkingDirectory (Get-Location) `
  -RedirectStandardOutput $log `
  -RedirectStandardError  "$dir\app-stderr.log" `
  -PassThru

$proc.Id.ToString() | Set-Content $pidF -NoNewline
Write-Host "PID=$($proc.Id)  guardado en $pidF"

$maxWait = 120
for ($i = 0; $i -lt $maxWait; $i++) {
    Start-Sleep -Seconds 1
    try {
        $r = Invoke-WebRequest -Uri 'http://localhost:8080/actuator/health' -TimeoutSec 2 -UseBasicParsing
        if ($r.StatusCode -eq 200) {
            Write-Host "App UP (health=$($r.StatusCode)) tras ${i}s"
            exit 0
        }
    } catch {
        # Tomcat aún no responde
    }
    if ($proc.HasExited) {
        Write-Error "El proceso Java terminó prematuramente (exit=$($proc.ExitCode)). Revisa $log"
        exit 1
    }
}

Write-Error "Timeout ${maxWait}s esperando /actuator/health"
exit 1