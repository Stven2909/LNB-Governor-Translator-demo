# Evidencia E3.2 — Corrección del agente de boletos y prueba discriminante HTTP vs FIXTURE

**Fecha:** 2026-11-06
**Rama de integración:** `feat/e3.2-integracion`
**Rama del agente:** `feat/e3.2-ticket-agent`
**Código evaluado en la corrida E2E:** `ff691af` (`docs(e3.2): alinear prompt y fixtures con source y sin nulos`)
**Alcance:** correcciones de nuestra parte (agente E3.2) tras auditar la respuesta de Carlos. **No** incluye una corrida real contra Sybase.

> **Advertencia de procedencia:** toda la evidencia de este documento es `provenance=SYNTHETIC` / `source=SEED`. No se afirma ningún dato `REAL`/`SYBASE`. La corrida real sigue bloqueada por dependencias externas (ver §7).

---

## 1. Resumen ejecutivo

La traza entregada por Carlos es consistente con el contrato, pero **no distingue** una consulta HTTP real al WS de una respuesta servida por el modo FIXTURE del agente. Además, el agente no exponía `source` y emitía `prize:null` / `collectionStatus:null` en casos donde el WS simplemente omite esos campos.

Se corrigieron ambos lados de nuestra responsabilidad:

1. El agente ahora **propaga `source`** (`SEED`/`SYBASE`) y **omite los campos nulos** con `@JsonInclude(NON_NULL)`.
2. El agente solo afirma una causa tipada (`DATABASE_UNAVAILABLE`, `DATA_INTEGRITY_ERROR`) si el WS la declara en el cuerpo; si no, conserva el estado HTTP con un código genérico `WS_ERROR`.
3. El runner E2E ahora exige `source`, inspecciona el JSON crudo y, si se le pasa el log del WS, exige **dos líneas `Procesando` por correlación** (una del runner y otra del agente).
4. Se añadió una prueba negativa que, con el WS caído, exige `502/503/504` con correlación. Un agente en FIXTURE respondería `200`.

Resultado de la corrida integrada: `=== E2E OK ===` en HTTP, `=== E2E FAIL (6 fallos) ===` en FIXTURE y `=== FAIL-CLOSED OK ===` con el WS caído.

---

## 2. Hallazgos de la auditoría a la respuesta de Carlos

| Hallazgo | Impacto | Corrección |
|---|---|---|
| La traza no discriminaba HTTP de FIXTURE | La evidencia no probaba que el agente llamara al WS | El runner exige `source` y dos líneas `Procesando` por correlación |
| El agente no exponía `source` | El llamador no podía distinguir `SEED` de `SYBASE` | `TicketQueryResult.source` y propagación desde `TicketToolResponse` |
| El agente emitía `prize:null` / `collectionStatus:null` | El shape público no coincidía con el WS, que omite esos campos | `@JsonInclude(NON_NULL)` en `TicketQueryResult` |
| Un `503` sin `DATABASE_UNAVAILABLE` se degradaba a `502` | Se afirmaba una causa que el WS no había declarado | `TicketWsUnavailableException` → `503 WS_ERROR` |
| Un `502` sin `DATA_INTEGRITY_ERROR` se trataba como inconsistencia confirmada | Se afirmaba corrupción de datos sin evidencia del WS | `TicketWsException` → `502 WS_ERROR` |
| El parser solo leía `code` | El WS canónico usa `error` en `503/502` | `errorCode()` lee `error` y cae a `code` |

La traza de Carlos sigue siendo válida como **contrato de DTO**. No se usa como aceptación E2E: el test `TicketAgentWithCarlosWsContractTest` levanta un `HttpServer` local y no reemplaza la corrida integrada.

---

## 3. Correcciones implementadas

### 3.1 Contrato de salida

- `worker-poc/src/main/java/com/pagaduriasintetica/worker/contract/ticket/TicketQueryResult.java`
  - Nuevo campo `source`.
  - `@JsonInclude(JsonInclude.Include.NON_NULL)` para omitir `prize`, `collectionStatus` y `source` cuando son nulos.
- `worker-poc/src/main/java/com/pagaduriasintetica/worker/agent/TicketAgent.java`
  - Normaliza `source` a mayúsculas y sin espacios.
  - Gate de procedencia: `REAL ⇔ SYBASE`, `SEED ⇔ SYNTHETIC`.
  - `source` desconocido o `provenance` inválida → `InvalidWsResponseException`.
  - En FIXTURE, `source` queda ausente (no se inventa).

### 3.2 Adaptador HTTP y fail-closed

- `worker-poc/src/main/java/com/pagaduriasintetica/worker/agent/tool/HttpTicketQueryTool.java`
  - `404` solo se traduce a `NOT_FOUND` si el cuerpo trae `found=false`; un 404 de ruta no se disfraza de boleto inexistente.
  - `503` con `DATABASE_UNAVAILABLE` → `TicketDatabaseUnavailableException`.
  - `503` sin esa causa → `TicketWsUnavailableException` (se conserva el 503).
  - `502` con `DATA_INTEGRITY_ERROR` → `TicketDataIntegrityException`.
  - `502` sin esa causa → `TicketWsException` (se conserva el 502).
  - `errorCode()` acepta `error` (contrato canónico) y `code` (compatibilidad).
- `worker-poc/src/main/java/com/pagaduriasintetica/worker/agent/exception/TicketWsUnavailableException.java`
  - Nueva excepción para el 503 sin causa declarada.
- `worker-poc/src/main/java/com/pagaduriasintetica/worker/agent/TicketAgentAdvice.java`
  - Handler de `TicketWsUnavailableException`: `503` con `code=WS_ERROR`, eco de `X-Correlation-Id` / `X-Request-Id`.

### 3.3 Runner E2E y prueba negativa

- `worker-poc/scripts/verify-agent-e2e.ps1`
  - Parámetros `-ExpectedSource`, `-ExpectedProvenance`, `-WsLogPath`.
  - Verifica `source` en WS y agente.
  - Inspecciona el JSON crudo para exigir ausencia de `prize` y `collectionStatus` en `NOT_AWARDED`/`NOT_FOUND`.
  - Con `-WsLogPath`, exige **2 líneas `Procesando consulta de boleto`** por correlación (runner + agente). En FIXTURE solo hay 1.
- `worker-poc/scripts/verify-agent-failclosed.ps1`
  - Con el WS caído, exige `502/503/504` y correlación ecoada.
  - Un agente en FIXTURE devolvería `200`, por lo que este script también discrimina el modo.

### 3.4 Tests

- `TicketQueryResultSerializationTest`: shape público con y sin `source`, sin nulos.
- `TicketAgentControllerTest`: `source:SEED` en 200; ausencia cruda de `prize`/`collectionStatus`; `503 WS_ERROR` genérico con correlación.
- `HttpTicketQueryToolTest`: `503/502` con shape canónico (`error`), con `code` antiguo, con código desconocido y sin cuerpo.
- `TicketAgentTest` / `TicketAgentHttpModeTest`: propagación y normalización de `source`; FIXTURE sin `source`.
- `TicketAgentWithCarlosWsContractTest`: assert `source:SEED` (contrato de DTO, no E2E).
- `AgentRealWsIT`: assert de coherencia `provenance`/`source` para la corrida real futura.

### 3.5 Prompt y fixtures

- `src/main/resources/prompts/ticket-agent-v0.1.txt`: el contrato de salida incluye `source` y no emite nulos.
- `src/main/resources/fixtures/tickets/*.json`: alineados con `source:SEED` y sin `prize`/`collectionStatus` nulos. Son ejemplos del contrato del WS; el stub `FixtureTicketQueryTool` los reemplaza en tiempo de ejecución y **no** declara `source` a propósito, para que el modo FIXTURE siga siendo distinguible del HTTP.

---

## 4. Commits

### 4.1 Rama `feat/e3.2-ticket-agent`

| SHA | Mensaje |
|---|---|
| `3bfcd54db3fdba45a8330947c1522a0bd74ffde5` | `fix(e3.2): exponer source, omitir nulos y endurecer fail-closed 503/502` |
| `67706186e4059968a18ff56ab89a40a659fb0c6e` | `fix(script): contar solo lineas Procesando en el log del WS` |
| `dd8331c458a3d3f40c4402eaf03fa4217071426d` | `docs(e3.2): alinear prompt y fixtures con source y sin nulos` |

### 4.2 Rama `feat/e3.2-integracion` (cherry-picks selectivos)

| SHA | Origen / contenido |
|---|---|
| `5b61bcb09f282cc93f2375db5c92859a0a477310` | cherry-pick de `3bfcd54` |
| `b126594c1534a7001aa9b16184825cf50b4648e1` | `test(e3.2): assert source SEED en contrato con WS de Carlos` |
| `ca050cc15c9bb8016978da9e7f43ba5bae6d3e1d` | cherry-pick de `6770618` |
| `ff691afad94258e6fa8dc69ae41e702fcf8cbe44` | cherry-pick de `dd8331c` |

No se abrió PR ni se mergeó `feat/e1-carlos-ticket-ws`. La integración fue por cherry-pick selectivo.

---

## 5. Pruebas automatizadas

| Rama / commit | Resultado |
|---|---|
| `feat/e3.2-ticket-agent` (`dd8331c`) | `Tests run: 161, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` |
| `feat/e3.2-integracion` (`ff691af`) | `Tests run: 193, Failures: 0, Errors: 0, Skipped: 1` — `BUILD SUCCESS` |

El único `skipped` es `SybaseTicketRealTest`, que exige `SYBASE_IT=true` y una conexión física a Sybase. No es un fallo ni una prueba ejecutada.

La salida completa de la suite integrada está en `mvn-test-output.txt`.

---

## 6. Evidencia E2E (2026-11-06)

### 6.1 Configuración

- WS de boletos: puerto `8085`, `APP_SYBASE_TICKET_ENABLED=false`, `APP_SYBASE_TICKET_SEED_ENABLED=true`.
- Agente HTTP: puerto `8082`, `APP_TICKET_AGENT_MODE=HTTP`, `APP_TICKET_AGENT_BASE_URL=http://localhost:8085`.
- Agente FIXTURE (control): puerto `8083`, `APP_TICKET_AGENT_MODE=FIXTURE`.
- Modelo determinista `FAKE`; sin Vertex ni credenciales externas.

`/status` del WS en la corrida:

```json
{"poolActive":false,"databaseSource":"Ninguna (Pool no conectado)","seedEnabled":true}
```

Esto confirma que la corrida es **SEED/SYNTHETIC**, no Sybase real. La traza cruda completa está en `e2e-http-seed.txt`.

### 6.2 Corrida HTTP (agente -> WS): `=== E2E OK ===`

- Exit code: `0`.
- 6 líneas `Procesando consulta de boleto` en el log del WS: 2 por correlación (runner + agente).
- Respuestas del agente con `source:SEED` y `provenance:SYNTHETIC`.
- `NOT_AWARDED` y `NOT_FOUND` sin `prize` ni `collectionStatus` en el JSON.

Fragmento de la traza cruda (`e2e-http-seed.txt`):

```json
{"traceId":"corr-e2e-0012345678","ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678","series":null,"checkDigit":null},"result":"FOUND","prize":{"amount":"50.00","currency":"USD","category":"CASH"},"collectionStatus":"PENDING","provenance":"SYNTHETIC","source":"SEED","message":"Boleto encontrado y premiado"}
{"traceId":"corr-e2e-0011111111","ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0011111111","series":null,"checkDigit":null},"result":"NOT_AWARDED","provenance":"SYNTHETIC","source":"SEED","message":"Boleto no premiado"}
{"traceId":"corr-e2e-9999999999","ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"9999999999","series":null,"checkDigit":null},"result":"NOT_FOUND","provenance":"SYNTHETIC","source":"SEED","message":"Boleto no encontrado"}
```

Nota: `series`/`checkDigit` aparecen nulos porque son campos opcionales de la clave de entrada que el agente ecoa; no son `prize` ni `collectionStatus`.

Las 6 líneas `Procesando` están en `ws-http-procesando-extract.txt`; el log completo del WS, en `ws-http.log`.

### 6.3 Control FIXTURE (debe fallar): `=== E2E FAIL (6 fallos) ===`

- Exit code: `1` (esperado).
- 3 líneas `Procesando` en un log limpio: solo las llamadas directas del runner; el agente FIXTURE no llamó al WS.
- 3 fallos por `source` ausente y 3 por conteo de log (`1` en vez de `2`).

```text
FAIL AGENTE 0012345678: source= esperado=SEED (FIXTURE no declara source)
FAIL AGENTE 0011111111: source= esperado=SEED (FIXTURE no declara source)
FAIL AGENTE 9999999999: source= esperado=SEED (FIXTURE no declara source)
FAIL WS log 0012345678: 1 apariciones de correlationId=corr-e2e-0012345678] Procesando consulta de boleto (esperadas 2: runner + agente)
```

Esto demuestra que el runner **no** pasa en FIXTURE y que la evidencia anterior no era discriminante. Archivos: `e2e-fixture-control.txt`, `ws-fixture.log`, `ws-fixture-procesando-extract.txt`.

### 6.4 Prueba negativa con WS caído: `=== FAIL-CLOSED OK ===`

- Exit code: `0`.
- Respuesta del agente: `502` con `code=WS_ERROR`, sin afirmar `DATABASE_UNAVAILABLE`.
- `X-Correlation-Id` y `X-Request-Id` ecoados.

```json
{"traceId":"corr-neg-20261106215131","code":"WS_ERROR","message":"Fallo de red al consultar WS de tickets"}
```

Archivo: `failclosed-ws-down.txt`.

---

## 7. Limitaciones y estado de E1 real

Esta evidencia **no cierra E1** ni prueba Sybase. El cierre real sigue bloqueado por:

1. **IAM:** falta `iap.tunnelResourceAccessor` / `compute.osLogin` para la VM; debe ejecutar Alex/IT o conceder acceso.
2. **Driver:** `jconn4.jar` no está disponible en el entorno actual.
3. **Credenciales Sybase read-only:** externas (Alex/Matthew).
4. **DDL de `LOTTERY_TICKETS`:** sin confirmación física de columnas y tipos.
5. **Boleto ganador real:** pendiente de confirmación de Alex/Matthew.

Además, `series` y `checkDigit` se aceptan en el contrato y se reenvían al WS, pero **no entran al `WHERE`** hasta que exista DDL físico confirmado. El agente los ecoa como `null` en `ticket`; no se usan para afirmar nada.

Criterio de cierre para la corrida real (no ejecutado aún):

```powershell
.\scripts\verify-agent-e2e.ps1 `
  -WsUrl http://localhost:8085 `
  -AgentUrl http://localhost:8080 `
  -ExpectedProvenance REAL `
  -ExpectedSource SYBASE `
  -WsLogPath <log-del-ws-real> `
  -OutFile docs/evidencia/<fecha>/e2e-http-real.txt
```

Debe devolver `=== E2E OK ===` con `source:SYBASE`, `provenance:REAL` y `poolActive:true`. Las fechas de entrega las confirma el responsable.

---

## 8. Reproducción

```powershell
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
$env:MAVEN_OPTS="-Xmx384m -XX:MaxMetaspaceSize=192m"

# Suite integrada
.\mvnw.cmd test

# Jar
.\mvnw.cmd -q -DskipTests package

# WS (terminal 1)
$env:PORT="8085"
$env:APP_SYBASE_TICKET_ENABLED="false"
$env:APP_SYBASE_TICKET_SEED_ENABLED="true"
java -jar target\worker-poc-0.0.1-SNAPSHOT.jar

# Agente HTTP (terminal 2)
$env:PORT="8082"
$env:APP_TICKET_AGENT_MODE="HTTP"
$env:APP_TICKET_AGENT_MODEL_CLIENT="FAKE"
$env:APP_TICKET_AGENT_BASE_URL="http://localhost:8085"
java -jar target\worker-poc-0.0.1-SNAPSHOT.jar

# Runner con log del WS
.\scripts\verify-agent-e2e.ps1 `
  -WsUrl http://localhost:8085 `
  -AgentUrl http://localhost:8082 `
  -OutFile docs/evidencia/2026-11-06/e2e-http-seed.txt `
  -WsLogPath <ruta-al-log-del-ws>

# Prueba negativa: detener el WS y luego
.\scripts\verify-agent-failclosed.ps1 `
  -AgentUrl http://localhost:8082 `
  -OutFile docs/evidencia/2026-11-06/failclosed-ws-down.txt
```

---

## 9. Archivos de evidencia

| Archivo | Contenido |
|---|---|
| `mvn-test-output.txt` | Suite integrada completa (193 tests, 1 skipped) |
| `e2e-http-seed.txt` | Traza cruda HTTP: status, cabeceras y cuerpos |
| `ws-http.log` | Log completo del WS en la corrida HTTP |
| `ws-http-procesando-extract.txt` | 6 líneas `Procesando` (2 por correlación) |
| `e2e-fixture-control.txt` | Traza del control FIXTURE que debe fallar |
| `ws-fixture.log` | Log completo del WS en el control FIXTURE |
| `ws-fixture-procesando-extract.txt` | 3 líneas `Procesando` (solo runner) |
| `failclosed-ws-down.txt` | Prueba negativa con WS caído (502 `WS_ERROR`) |
| `agent-http.log` / `agent-fixture.log` | Arranque de ambos agentes para reproducibilidad |
