# Guion de sesión — [E1][CASO 1] · viernes 13/11/2026

**Objetivo:** demostrar la ruta completa Agente → WS y el camino de salida del worker, y
mostrar el punto exacto donde se detiene la integración real con Sybase.

**Duración:** 20–25 minutos.
**Conduce:** Steven Rivera.
**Participan:** Carlos (WS/Sybase), Henry (evidencia), Kimi/Luis (Cloud Run/Gateway), Alex/Matthew
(clave real y recepción), JD/Wendy (dirección).

> **Regla de oro de la sesión:** no se declara cierre, no se dice `REAL`, no se dice `SYBASE`.
> La frase correcta es: *"Paso 1 de cableado ejecutado; Paso 2 pendiente por insumos de
> plataforma y Sybase."*

---

## 0. Mapa de ventanas

```
Ventana A  →  WS        (se queda corriendo, puerto 8085)
Ventana B  →  Agente    (se queda corriendo, puerto 8080)
Ventana C  →  pruebas   (aquí se escriben los comandos)
```

Todas en `C:\Users\steve\Documents\Agentes y Vertex\worker-poc`.

---

## 1. Preparación (antes de conectarse)

```powershell
cd "C:\Users\steve\Documents\Agentes y Vertex\worker-poc"
Get-NetTCPConnection -LocalPort 8080,8085 -State Listen -ErrorAction SilentlyContinue
```

**Esperado:** sin salida. Si hay algo, cerrar ese proceso antes de empezar.

```powershell
mvn -DskipTests package
Get-Item target\worker-poc-0.0.1-SNAPSHOT.jar | Select-Object Name,Length,LastWriteTime
```

**Esperado:** `BUILD SUCCESS` y el jar con fecha de hoy.

```powershell
New-Item -ItemType Directory -Force docs\evidencia\2026-11-13 | Out-Null
```

**Qué decir:** *"Antes de arrancar, dejo claro el alcance: vamos a ver la ruta funcionando y
vamos a ver el bloqueo exacto. No vamos a presentar una semilla como si fuera Sybase."*

---

## 2. Levantar el WS (Ventana A)

```powershell
java -jar target\worker-poc-0.0.1-SNAPSHOT.jar --server.port=8085 --APP_SYBASE_TICKET_SEED_ENABLED=true --APP_SYBASE_TICKET_ENABLED=false --APP_SYBASE_TICKET_PROVENANCE=SYNTHETIC
```

**Esperado:**
```
Tomcat started on port 8085 (http)
Started WorkerPocApplication in X.XXX seconds
```
El cursor queda bloqueado. No escribir más en esta ventana.

**Verificar (Ventana C):**
```powershell
curl.exe -s http://localhost:8085/actuator/health/readiness
```
**Esperado:** `{"status":"UP"}`

```powershell
curl.exe -s http://localhost:8085/api/v1/tickets/status
```
**Esperado:**
```json
{"poolActive":false,"databaseSource":"Ninguna (Pool no conectado)","seedEnabled":true,
 "lastError":"Sybase JDBC deshabilitado por configuración (app.sybase.ticket.enabled=false)",
 "lastQueryError":null,"lastCheck":"..."}
```

**Qué decir (señalando `poolActive:false`):**
> "Este `false` es la prueba de que no hay Sybase conectado. El WS está arriba, el contrato
> funciona, pero el pool está apagado a propósito. No lo vamos a disfrazar."

**Qué NO decir:** "el WS está conectado a Sybase", "el pool está listo".

---

## 3. Consulta directa al WS (Ventana C)

```powershell
$wsBody = '{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}'
curl.exe -s -X POST http://localhost:8085/api/v1/tickets/lookup -H "Content-Type: application/json" -H "X-Correlation-Id: demo-ws-001" --data-raw $wsBody
```

**Esperado:** `found:true`, `awarded:true`, `prize 50.00 USD CASH`, `collectionStatus:PENDING`,
`provenance:SYNTHETIC`, `source:SEED`.

**Qué decir:**
> "Este es el contrato del WS: boleto encontrado, premiado, con monto, moneda, estado de cobro
> y procedencia. La correlación viaja en las cabeceras y vuelve igual."

**Suite del WS con evidencia:**
```powershell
.\scripts\verify-carlos-ws.ps1 -BaseUrl http://localhost:8085 -RawOutputFile docs\evidencia\2026-11-13\ws-2026-11-13.txt
```

**Esperado:** `[1/4]` a `[4/4]` con `EXITO`, correlación `corr-carlos-001`, y:
```
TODAS LAS VALIDACIONES COMPLETADAS EXITOSAMENTE (EXIT CODE 0)
```

**Qué decir:**
> "Cuatro validaciones: estado del pool, boleto premiado, boleto no premiado y boleto
> inexistente. Las cuatro pasan. Nada de esto toca Sybase todavía."

---

## 4. Levantar el agente (Ventana B)

```powershell
java -jar target\worker-poc-0.0.1-SNAPSHOT.jar --server.port=8080 --APP_TICKET_AGENT_MODE=HTTP --APP_TICKET_AGENT_BASE_URL=http://localhost:8085 --APP_TICKET_AGENT_MODEL_CLIENT=FAKE
```

**Esperado:**
```
Tomcat started on port 8080 (http)
Started WorkerPocApplication in X.XXX seconds
```

**Qué decir:**
> "El agente está en modo HTTP, apuntando al WS de Carlos. Uso el cliente de modelo `FAKE`
> para que esta prueba no dependa de Vertex ni de cuota. La lógica de integración es la real."

**Verificar:**
```powershell
curl.exe -s http://localhost:8080/actuator/health/readiness
```
**Esperado:** `{"status":"UP"}`

---

## 5. El recorrido completo (Ventana C)

**Caso 1 — boleto ganador:**
```powershell
$body = '{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}'
curl.exe -s -X POST http://localhost:8080/api/v1/agent/ticket-query -H "Content-Type: application/json" -H "X-Correlation-Id: demo-001" --data-raw $body
```

**Esperado:** `result:"FOUND"`, `traceId:"demo-001"`, `prize 50.00`, `provenance:SYNTHETIC`,
`source:SEED`.

**Qué decir (y mirar la Ventana A en ese momento):**
> "Equipo 2 manda la clave al agente. El agente consulta el WS de Carlos. El WS responde con el
> boleto, el premio y el estado de cobro. Miren la ventana del WS: aparece la línea de consulta.
> Eso es la llamada real, no un mock."

**Controles:**
```powershell
foreach ($t in '0011111111','9999999999') {
  $payload = @{ ticket = @{ lotteryCode='LNB'; drawNumber='2026-10-15'; ticketNumber=$t } } | ConvertTo-Json -Compress
  Write-Host "--- $t"
  Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/v1/agent/ticket-query -ContentType 'application/json' -Headers @{ 'X-Correlation-Id'="demo-$t" } -Body $payload | ConvertTo-Json -Depth 6
}
```

**Esperado:**
- `0011111111` → `result:"NOT_AWARDED"`, sin `prize`
- `9999999999` → `result:"NOT_FOUND"`, sin `prize`

**Qué decir:**
> "Los dos casos de control: boleto válido no premiado y boleto inexistente. En ninguno se
> inventa premio ni se rellena con nulos. `NOT_FOUND` no es un 404: es 200 con `found:false`."

**E2E con evidencia:**
```powershell
.\scripts\verify-agent-e2e.ps1 -WsUrl http://localhost:8085 -AgentUrl http://localhost:8080 -OutFile docs\evidencia\2026-11-13\e2e-2026-11-13.txt
```

**Esperado:** tres pares WS/Agente con `200`, correlación ecoada, y:
```
=== E2E OK ===
```

**Qué decir:**
> "La traza completa queda guardada: request, response, cabeceras de correlación y los tres
> casos. Esto es lo que Henry puede auditar."

---

## 6. Prueba negativa — fail-closed (cierra la duda del mock)

1. En la **Ventana A**, `Ctrl+C` para detener el WS.
2. En la **Ventana C**:

```powershell
.\scripts\verify-agent-failclosed.ps1 -AgentUrl http://localhost:8080 -OutFile docs\evidencia\2026-11-13\failclosed-2026-11-13.txt
```

**Esperado:**
```
--- AGENTE 502
{"traceId":"corr-neg-...","code":"WS_ERROR","message":"Fallo de red al consultar WS de tickets"}
=== FAIL-CLOSED OK ===
```

**Qué decir:**
> "Ahora apagué el WS. Si el agente estuviera usando un fixture escondido, respondería 200.
> Responde 502 `WS_ERROR` conservando la correlación. Eso demuestra que sale a la red y que
> falla cerrado: si no hay WS, no hay respuesta inventada."

**Este es el momento más fuerte de la sesión.** No lo saltees.

---

## 7. Recorrido inverso — sistema → Sybase

1. En la **Ventana B**, `Ctrl+C` para detener el agente (libera el 8080).
2. En la **Ventana C**:

```powershell
.\scripts\demo-live-start.ps1
```
**Esperado:** `PID=... guardado` y `App UP (health=200) tras Ns`.

```powershell
.\scripts\demo-live-run.ps1
```
**Esperado:** 7 envelopes, **7/7 HTTP 200** (`SUCCEEDED`, `IDEMPOTENT`, `REJECTED` ×2,
`DLQ_QUARANTINED` ×3).

```powershell
.\scripts\demo-live-stop.ps1
```

**Qué decir:**
> "Este es el camino de salida: evento, validación, catálogo, reserva atómica, JDBC y reporte.
> Funciona completo con `FixtureJdbcExecutor`. Ese es el único punto mock: aquí entra
> `jConnect` y aquí se escribe en Sybase. No escribimos en G13 ni en producción."

**Qué NO decir:** "ya escribe en Sybase", "el flujo de Pagaduría está cerrado".

---

## 8. El bloqueo exacto (pantalla o pizarra)

| Paso donde se detiene | Qué falta | Quién |
|---|---|---|
| Despliegue en Cloud Run | repo corporativo, permisos, URLs de WS y agente | Kimi / Luis |
| Agente → WS privado | `HttpTicketQueryTool` no envía `Authorization`; riesgo de 403 | Kimi / Luis + parche de Steven |
| `/status` con `poolActive:true` | `jconn4` no está en la imagen, IAM, credenciales read-only | Carlos + Cloudfly |
| Consulta real | DDL/columnas de `LOTTERY_TICKETS` | Carlos / Alex |
| Resultado de negocio | clave del boleto ganador real | Alex / Matthew |
| Escritura controlada | autorización institucional, entorno y tabla destino | LNB / JD |

**Qué decir:**
> "De estos seis, ninguno es código pendiente de nuestro lado. Son permisos, credenciales y una
> decisión institucional. Por eso el caso no se cierra hoy, y por eso no lo voy a declarar
> cerrado."

---

## 9. Cierre y decisiones

**Qué decir:**
> "Resumen: la ruta Sybase → sistema está construida y probada hasta el WS, con semilla y con
> prueba negativa. La ruta sistema → Sybase está probada hasta el punto de sustitución JDBC.
> Lo que falta es acceso, no implementación. El cierre se declara solo en el hito 86bd24hhg
> cuando Equipo 2 reciba la misma clave con provenance REAL y source SYBASE."

**Las tres decisiones que hay que pedir:**

1. ¿Se autoriza desplegar desde este repositorio o se entrega por otra vía? (desbloquea a Kimi/Luis)
2. ¿Auth service-to-service opción A (ID token) u opción B (internal + unauthenticated para demo)?
3. ¿Quién habilita el acceso a Sybase DEV y en qué ventana? Sin eso, `poolActive:true` no existe.

---

## 10. Preguntas probables y respuesta corta

| Pregunta | Respuesta |
|---|---|
| "¿Esto está cerrado?" | "No. Paso 1 de cableado ejecutado; Paso 2 pendiente por insumos." |
| "¿El premio es real?" | "No. Es el fixture autorizado del Caso 1. La clave real la entrega Alex/Matthew." |
| "¿Por qué no hay Sybase?" | "`jconn4` no está en la imagen, faltan IAM y credenciales read-only, y falta el DDL confirmado." |
| "¿Cuánto falta?" | "Depende de terceros. Con los insumos, la corrida real es cuestión de horas, no de semanas." |
| "¿Por qué no lo probaron antes?" | "Porque el código del WS llegó el 04e33c4 y la integración se cerró el 06/11; desde ahí está todo listo para el acceso." |
| "¿El agente inventa datos?" | "No: con el WS caído responde 502 `WS_ERROR`, y si `provenance=REAL` no trae `source=SYBASE`, rechaza." |
| "¿Se tocó G13?" | "No. Solo lectura y solo entorno autorizado. No se escribió en G13." |

---

## 11. Si llegan URLs a última hora

Orden de verificación, sin cambiar nada del guion:

```powershell
curl.exe -s https://<URL_WS>/actuator/health/readiness
curl.exe -s https://<URL_WS>/api/v1/tickets/status
.\scripts\verify-carlos-ws.ps1 -BaseUrl https://<URL_WS>
.\scripts\verify-agent-e2e.ps1 -WsUrl https://<URL_WS> -AgentUrl https://<URL_AGENTE>
```

Si el WS es privado y responde 403, **eso es el hallazgo**: el cliente del agente no envía
`Authorization`. Se documenta como bloqueo exacto y se nombra al responsable.

---

## 12. Evidencia que queda

| Archivo | Qué prueba |
|---|---|
| `docs/evidencia/2026-11-13/ws-2026-11-13.txt` | contrato del WS, 4 casos |
| `docs/evidencia/2026-11-13/ws-status-2026-11-13.json` | `poolActive:false` y `seedEnabled:true` |
| `docs/evidencia/2026-11-13/e2e-2026-11-13.txt` | agente → WS con correlación |
| `docs/evidencia/2026-11-13/failclosed-2026-11-13.txt` | 502 `WS_ERROR` con WS caído |
| `docs/evidencia/2026-11-06/mvn-test-output.txt` | 193 / 192 / 1 skipped |

```powershell
git add docs/evidencia/2026-11-13
git commit -m "docs(evidencia): corrida integral Caso 1 2026-11-13 (semilla + fail-closed)"
git push origin feat/e3.2-integracion
```

---

## 13. Anexo técnico

| Ítem | Valor |
|---|---|
| Rama | `feat/e3.2-integracion` @ `4ebab03` (docs) · código `8f9b844` |
| WS de Carlos | `feat/e1-carlos-ticket-ws` @ `04e33c4` |
| Jar | `target\worker-poc-0.0.1-SNAPSHOT.jar` |
| Puertos | WS `8085` · Agente `8080` |
| Endpoints | `POST /api/v1/tickets/lookup` · `GET /api/v1/tickets/status` · `POST /api/v1/agent/ticket-query` |
| Health | `/actuator/health/readiness` |
| Variables WS | `APP_SYBASE_TICKET_SEED_ENABLED`, `APP_SYBASE_TICKET_ENABLED`, `APP_SYBASE_TICKET_PROVENANCE` |
| Variables Agente | `APP_TICKET_AGENT_MODE`, `APP_TICKET_AGENT_BASE_URL`, `APP_TICKET_AGENT_MODEL_CLIENT` |
| Correos | `error` (WS) · `code` (agente) |
| Cierre | solo en `https://app.clickup.com/t/86bd24hhg` |
