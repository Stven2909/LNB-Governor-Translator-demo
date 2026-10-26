# Levanta el worker con el ticket-agent en modo VERTEX real.
# Requisitos previos (una sola vez, con la cuenta corporativa):
#   1. gcloud auth login steven@avanzatech.xyz
#   2. gcloud config set project proy-comercial-dev-lnb
#   3. gcloud auth application-default login
#   4. gcloud auth application-default set-quota-project proy-comercial-dev-lnb
# El WS de tickets sigue en modo FIXTURE (evaluacion controlada, sin datos reales).
param(
    [string]$ProjectId = "proy-comercial-dev-lnb",
    [string]$Region = "global",
    [string]$Model = "gemini-3.8-flash"
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

$env:APP_TICKET_AGENT_MODEL_CLIENT = "VERTEX"
$env:APP_VERTEX_PROJECT_ID = $ProjectId
$env:APP_TICKET_AGENT_REGION = $Region
$env:APP_TICKET_AGENT_MODEL = $Model

Write-Host "Java:         $env:JAVA_HOME"
Write-Host "Ticket agent: VERTEX | project=$ProjectId | region=$Region | model=$Model"
Write-Host "WS tickets:   FIXTURE (default)"
Write-Host ""

Set-Location (Split-Path $PSScriptRoot -Parent)
.\mvnw.cmd spring-boot:run
