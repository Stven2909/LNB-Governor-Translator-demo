# Levanta el worker con el ticket-agent en modo VERTEX real.
# Requisitos previos (una sola vez, con la cuenta corporativa):
#   1. gcloud auth login steven@avanzatech.xyz
#   2. gcloud config set project proy-comercial-dev-lnb
#   3. gcloud auth application-default login
#   4. gcloud auth application-default set-quota-project proy-comercial-dev-lnb
# Por defecto el WS de tickets queda en FIXTURE (evaluacion controlada, sin datos reales).
# Para probar el modo HTTP contra el MOCK local:
#   .\scripts\mock-ticket-ws.ps1                       (terminal 1)
#   .\scripts\run-vertex.ps1 -WsMode HTTP              (terminal 2)
# Si no hay credenciales de Vertex vigentes, usar -ModelClient FAKE:
#   .\scripts\run-vertex.ps1 -WsMode HTTP -ModelClient FAKE
# MOCK: el WS real de Carlos aun NO esta disponible; 127.0.0.1:9090 es simulado.
param(
    [string]$ProjectId = "proy-comercial-dev-lnb",
    [string]$Region = "global",
    [string]$Model = "gemini-3.8-flash",
    [ValidateSet("FAKE", "VERTEX")]
    [string]$ModelClient = "VERTEX",
    [ValidateSet("FIXTURE", "HTTP")]
    [string]$WsMode = "FIXTURE",
    [string]$WsBaseUrl = "http://127.0.0.1:9090",
    [string]$WsPath = "/api/v1/tickets/lookup",
    [string]$WsTimeout = "5s"
)

$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $candidate = "C:\Program Files\Java\jdk-21"
    if (Test-Path (Join-Path $candidate "bin\java.exe")) {
        $env:JAVA_HOME = $candidate
    }
}

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JAVA_HOME no configurado y no se encontro C:\Program Files\Java\jdk-21"
}

$env:APP_TICKET_AGENT_MODEL_CLIENT = $ModelClient
$env:APP_VERTEX_PROJECT_ID = $ProjectId
$env:APP_TICKET_AGENT_REGION = $Region
$env:APP_TICKET_AGENT_MODEL = $Model
$env:APP_TICKET_AGENT_MODE = $WsMode

if ($WsMode -eq "HTTP") {
    $env:APP_TICKET_AGENT_BASE_URL = $WsBaseUrl
    $env:APP_TICKET_AGENT_LOOKUP_PATH = $WsPath
    $env:APP_TICKET_AGENT_TIMEOUT = $WsTimeout
}

Write-Host "Java:         $env:JAVA_HOME"
Write-Host "Ticket agent: $ModelClient | project=$ProjectId | region=$Region | model=$Model"
if ($WsMode -eq "HTTP") {
    Write-Host "WS tickets:   HTTP -> $WsBaseUrl$WsPath | timeout=$WsTimeout"
    Write-Host "MOCK: si apunta a 127.0.0.1 es el WS simulado; Carlos aun no entrega el real."
}
else {
    Write-Host "WS tickets:   FIXTURE (sintetico)"
}
Write-Host ""

Set-Location (Split-Path $PSScriptRoot -Parent)
.\mvnw.cmd spring-boot:run
