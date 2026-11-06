# Evidencia E1 / E3.0 — Web Service de Consulta de Boletos y Pool Sybase

**Fecha:** 2026-11-05  
**Responsable:** Carlos Herrera (`carlos@avanzatech.xyz`)  
**Rama Oficial:** `feat/e1-carlos-ticket-ws`  
**Tareas ClickUp atendidas:**  
- `[E1][CARLOS][URGENTE] Entregar WS de un boleto ganador real en Sybase`  
- `[E1][APOYO] JDBC y pool incluidos en el WS de Carlos`  
- `[E1][APOYO] Objeto y llave Sybase incluidos en el WS de Carlos`  
- `[E1][CASO 1] Devolver a Equipo 2 un boleto ganador consultado en Sybase`  
- `E3.0 — Contrato mínimo y servicio ejecutable de consulta de boletos`  

---

## 1. Resumen Ejecutivo y Diagnóstico Técnico

Siguiendo las decisiones cerradas y la retroalimentación técnica oficial del equipo (Steven Rivera y Jorge Daniel López - JD):
1. **Contrato Canónico Cerrado:** Se consolidó el contrato oficial de `TicketLookupResponse` con shape estructurado (`found`, `awarded`, `ticketNumber`, `prize{amount, currency, category}`, `collectionStatus`, `provenance`, `source`, `databaseSource`, `message`). El JSON preliminar del correo queda formal y definitivamente retirado.
2. **Esquema Fail-Closed Estricto (Fallo Seguro 503):** Si el pool de Sybase no está activo o la consulta a la base de datos falla, el servicio responde inmediatamente con **`HTTP 503 Service Unavailable`** con cuerpo tipado y saneado:
   ```json
   {
     "error": "DATABASE_UNAVAILABLE",
     "message": "Pool Sybase no disponible",
     "correlationId": "..."
   }
   ```
   La semilla (`resolveAuthorizedSeed`) queda desactivada por defecto (`app.sybase.ticket.seed-enabled=false`) y nunca entra en la ruta de ejecución sin habilitación explícita mediante `APP_SYBASE_TICKET_SEED_ENABLED=true`.
3. **Honestidad Rigurosa en la Procedencia:**
   - `provenance: "REAL"` y `source: "SYBASE"` se emiten **únicamente** cuando los datos provienen de una fila física (`ResultSet.next()`) de Sybase ASE con `poolActive: true`.
   - Cuando se activa el perfil de desarrollo offline sin conexión VPN a Sybase DEV, el servicio declara con total transparencia **`provenance: "SYNTHETIC"`** y **`source: "SEED"`**. Nunca se afirma procedencia real sin conexión física.
4. **Driver Sybase Institucional (jConnect) y Eliminación de jTDS:**
   - El driver corporativo oficial autorizado por JD/LNB es `com.sybase.jdbc4.jdbc.SybDriver` (`jconn4.jar`).
   - Se removió completamente `jtds` del `pom.xml` y se eliminó todo código de fallback a jTDS en el repositorio.
   - Debido a restricciones de licencia de SAP/Sybase, el binario `.jar` no se commitea en repositorios públicos; se instala en el repositorio local o privado institucional:
     ```bash
     mvn install:install-file -Dfile=jconn4.jar -DgroupId=com.sybase.jdbc4 -DartifactId=jconn4 -Dversion=16.0 -Dpackaging=jar
     ```
5. **Fidelidad de Datos y Estados:**
   - `collectionStatus`: Se mapea fielmente desde la columna `collection_status` del `ResultSet` de Sybase; no se inventa ni se fija `"PENDING"`.
   - `normalizeAmount`: Valida que `prize_amount` sea parseable y no nulo. Se escala estrictamente a 2 decimales (`HALF_UP`). En caso de nulidad o formato inválido, falla cerrado sin inventar montos (0.00).
   - `databaseSource`: Se expone mediante etiqueta lógica limpia: `"Sybase ASE LNB DEV"`, sin filtrar URLs de conexión ni direcciones IP internas.
6. **Configuración Limpia y Aislada:**
   - Sin IPs ni credenciales hardcodeadas en código; se resuelven mediante variables de entorno y Secret Manager.
   - `app.sybase.ticket.enabled=false` por defecto globalmente para no interferir en CI/CD ni en la rama de Steven.
   - El puerto por defecto del Worker se mantiene intacto (`PORT:8080`) y el entorno de Carlos opera de forma desacoplada con el perfil `application-carlos.properties` (`PORT:8085`).
7. **Correlación Distribuida:** El servicio captura `X-Correlation-Id` y `X-Request-Id` y los ecoa obligatoriamente en los headers HTTP de respuesta y en los logs.

---

## 2. Contrato de Entrada y Salida Canónico

### A. Endpoint
`POST /api/v1/tickets/lookup`

**Headers de Entrada:**
- `Content-Type: application/json`
- `X-Correlation-Id: <uuid o id de correlación>`
- `X-Request-Id: <uuid opcional>`

**Headers de Respuesta:**
- `Content-Type: application/json`
- `X-Correlation-Id: <mismo id de correlación recibido>`
- `X-Request-Id: <mismo request id recibido>`

### B. Payload de Entrada (Request)
Acepta la clave compuesta del boleto (`drawNumber` como String):
```json
{
  "lotteryCode": "LNB",
  "drawNumber": "2026-10-15",
  "ticketNumber": "<real>",
  "series": "A",
  "checkDigit": "5"
}
```

### C. Respuestas Tipadas Canónicas

#### 1. Boleto Ganador (Premiado)
```json
{
  "found": true,
  "awarded": true,
  "ticketNumber": "<real>",
  "prize": {
    "amount": "50.00",
    "currency": "USD",
    "category": "CASH"
  },
  "collectionStatus": "PENDING",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE LNB DEV",
  "message": "Boleto encontrado y premiado"
}
```

#### 2. Boleto No Premiado
```json
{
  "found": true,
  "awarded": false,
  "ticketNumber": "<real>",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE LNB DEV",
  "message": "Boleto sin premio asignado"
}
```
*(Debido a `@JsonInclude(NON_NULL)`, el campo `prize` se omite limpiamente)*.

#### 3. Boleto No Encontrado
```json
{
  "found": false,
  "awarded": false,
  "ticketNumber": "<solicitado>",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE LNB DEV",
  "message": "Boleto no encontrado"
}
```

#### 4. Fallo Seguro (HTTP 503 Service Unavailable)
Si el pool no está activo y la semilla está deshabilitada (comportamiento por defecto):
```json
{
  "error": "DATABASE_UNAVAILABLE",
  "message": "Pool Sybase no disponible",
  "correlationId": "trace-fail-closed-001"
}
```

---

## 3. Diagnóstico y Salud del Pool JDBC

### Endpoint de Estado:
`GET /api/v1/tickets/status`

**Salida con Pool Sybase Activo:**
```json
{
  "poolActive": true,
  "seedEnabled": false,
  "lastError": null,
  "lastCheck": "2026-11-05T22:30:00Z"
}
```

**Salida en Entorno Offline (Sin VPN / Modo Fail-Closed):**
```json
{
  "poolActive": false,
  "seedEnabled": false,
  "lastError": "Infraestructura Sybase DEV no alcanzable...",
  "lastCheck": "2026-11-05T22:30:00Z"
}
```

---

## 4. Clasificación y Estado de la Suite de Pruebas

La suite completa comprende **145 pruebas**:
- **144 pruebas automatizadas (Unitarias y de Contrato) en VERDE (100% PASS):**
  - `TicketLookupControllerTest` (7 pruebas): Valida comportamiento 503 Fail-Closed saneado, boleto ganador, omisión de prize en no premiado, eco de `X-Correlation-Id`, endpoint `/status` con `seedEnabled`, y validación de `HTTP 400 INVALID_INPUT`.
  - `TicketAgentWithCarlosWsContractTest` (3 pruebas): Valida el contrato HTTP directo entre el agente de Steven y el WS de Carlos con shape canónico.
  - Pruebas del Worker, Governor, Pipeline y contratos generales (134 pruebas).
- **1 prueba de integración real (`SybaseTicketRealTest`):**
  - Anotada con `@EnabledIfEnvironmentVariable(named = "SYBASE_IT", matches = "true")`.
  - En CI/CD y entornos locales sin VPN a Sybase DEV, JUnit la reporta como **`SKIPPED`** (1 skipped) de forma segura y transparente, sin fallar el build.
  - Al ejecutarse con `SYBASE_IT=true` en la VM con VPN, valida `found=true`, `awarded=true`, `prize.amount` normalizado, `collectionStatus` de la fila, `provenance: REAL`, `source: SYBASE` y `databaseSource: "Sybase ASE LNB DEV"`.

---

## 5. Instrucciones de Ejecución y Verificación

### A. Ejecución de pruebas automatizadas:
```powershell
.\mvnw.cmd test "-Daether.connector.https.securityMode=insecure"
```

### B. Ejecución del servicio localmente (Perfil Carlos):
```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=carlos" "-Daether.connector.https.securityMode=insecure"
```

### C. Verificación mediante script:
```powershell
# Modo desarrollo local:
.\scripts\verify-carlos-ws.ps1

# Modo estricto para corrida real con VPN:
.\scripts\verify-carlos-ws.ps1 -StrictMode -RealTicket <numero_boleto_confirmado>
```
