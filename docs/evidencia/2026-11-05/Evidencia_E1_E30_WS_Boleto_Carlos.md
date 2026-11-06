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
3. **Manejo Tipado de Integridad de Datos (HTTP 502):** Si un boleto premiado devuelto por Sybase presenta inconsistencias de datos (por ejemplo, `prize_amount` nulo, menor o igual a 0.00, o con más de dos decimales significativos sin regla de redondeo autorizada), el servicio lanza `SybaseDataIntegrityException` y responde con **`HTTP 502 Bad Gateway`** (`error: "DATA_INTEGRITY_ERROR"`), diferenciando nítidamente una inconsistencia de datos de un fallo de conexión del pool.
4. **Honestidad Rigurosa en la Procedencia:**
   - `provenance: "REAL"` y `source: "SYBASE"` se emiten **únicamente** cuando los datos provienen de una fila física (`ResultSet.next()`) de Sybase ASE con `poolActive: true`.
   - Cuando se activa el perfil de desarrollo offline sin conexión VPN a Sybase DEV, el servicio declara con total transparencia **`provenance: "SYNTHETIC"`** y **`source: "SEED"`**. Nunca se afirma procedencia real sin conexión física.
5. **Driver Sybase Institucional (jConnect) vía Perfil Maven `-Psybase`:**
   - El driver corporativo oficial autorizado por JD/LNB es `com.sybase.jdbc4.jdbc.SybDriver` (`jconn4.jar`).
   - Se removió completamente `jtds` del `pom.xml` y se eliminó todo código de fallback a jTDS en el repositorio.
   - Para no romper builds en CI/CD donde el jar comercial no está disponible en Maven Central, la dependencia `com.sybase.jdbc4:jconn4:16.0` se declara en el perfil Maven `sybase` (`-Psybase`).
   - En la máquina o VM de despliegue con VPN, el binario institucional se registra en el repositorio local `.m2` mediante:
     ```bash
     mvn install:install-file -Dfile=jconn4.jar -DgroupId=com.sybase.jdbc4 -DartifactId=jconn4 -Dversion=16.0 -Dpackaging=jar
     ```
   - Al compilar o arrancar con `-Psybase`, Maven inyecta formalmente `jconn4` al classpath de ejecución.
6. **Fidelidad de Datos y Regla de Escala Decimal:**
   - `collectionStatus`: Se mapea fielmente desde la columna `collection_status` del `ResultSet` de Sybase; no se inventa ni se fija `"PENDING"`.
   - `normalizeAmount`: Emplea `RoundingMode.UNNECESSARY` y valida `bd > 0`. No redondea hacia arriba (`HALF_UP`) valores como `50.005` sin autorización de JD. Admite tipos de datos como Sybase `money` con ceros irrelevantes a la derecha (`50.0000` -> `50.00`). Ante escalas mayores a 2 decimales significativos o montos no positivos, rechaza la operación fallando cerrado con `SybaseDataIntegrityException`.
   - `databaseSource`: Se expone mediante etiqueta lógica limpia: `"Sybase ASE LNB DEV"`, sin filtrar URLs de conexión ni direcciones IP internas.
7. **Timeout de Inicialización del Pool Configurable:**
   - `initializationFailTimeout` se hace configurable mediante `app.sybase.ticket.initialization-fail-timeout` (default: 10s), evitando que Hikari aborte prematuramente a 1 segundo en conexiones VPN con latencia.
8. **Configuración Limpia y Aislada:**
   - Sin IPs ni credenciales hardcodeadas en código; se resuelven exclusivamente mediante variables de entorno y Secret Manager.
   - `app.sybase.ticket.enabled=false` por defecto globalmente para no interferir en CI/CD ni en la rama de Steven.
   - El puerto por defecto del Worker se mantiene intacto (`PORT:8080`) y el entorno de Carlos opera de forma desacoplada con el perfil `application-carlos.properties` (`PORT:8085`).
9. **Correlación Distribuida:** El servicio captura `X-Correlation-Id` y `X-Request-Id`. Para garantizar trazabilidad unívoca a través de todos los microservicios, el servicio ecoa el `correlationId` resuelto tanto en `X-Correlation-Id` como en `X-Request-Id` en las cabeceras HTTP de respuesta y en los registros de log.

---

## 2. Variables de Entorno del Servicio

Para preservar la seguridad y reproducibilidad del entorno, la configuración se administra mediante variables de entorno (únicamente nombres, sin credenciales en texto plano):

| Variable de Entorno | Tipo | Descripción | Default |
|---------------------|------|-------------|---------|
| `APP_SYBASE_TICKET_URL` | String | Cadena JDBC de conexión a Sybase ASE DEV | Vacío |
| `APP_SYBASE_TICKET_USERNAME` | String | Usuario institucional de base de datos | Vacío |
| `APP_SYBASE_TICKET_PASSWORD` | String | Contraseña de autenticación JDBC | Vacío |
| `APP_SYBASE_TICKET_ENABLED` | Boolean | Habilitar pool JDBC de Sybase | `false` |
| `APP_SYBASE_TICKET_SEED_ENABLED` | Boolean | Habilitar fixture simulado para pruebas locales offline | `false` |
| `APP_SYBASE_TICKET_INIT_TIMEOUT` | Duration | Timeout de fallo de inicialización en HikariCP | `10s` |
| `SYBASE_IT` | Boolean | Activar ejecución física de `SybaseTicketRealTest` | `false` |
| `SYBASE_REAL_TICKET` | String | Número de boleto real confirmado por Alex/Matthew | Requerido si `SYBASE_IT=true` |
| `SYBASE_REAL_DRAW` | String | Identificador del sorteo real en Sybase | Requerido si `SYBASE_IT=true` |
| `SYBASE_REAL_LOTTERY` | String | Código de lotería institucional (ej. `LNB`) | Requerido si `SYBASE_IT=true` |
| `SYBASE_REAL_AMOUNT` | String | Monto esperado de premio para validación (ej. `50.00`) | `50.00` |
| `SYBASE_REAL_CURRENCY` | String | Moneda esperada (ej. `USD`) | `USD` |
| `SYBASE_REAL_STATUS` | String | Estado de cobro esperado en la fila de base de datos | Fila de BD |

---

## 3. Contrato de Entrada y Salida Canónico

### A. Endpoint
`POST /api/v1/tickets/lookup`

**Headers de Entrada:**
- `Content-Type: application/json`
- `X-Correlation-Id: <uuid o id de correlación>`
- `X-Request-Id: <uuid opcional>`

**Headers de Respuesta:**
- `Content-Type: application/json`
- `X-Correlation-Id: <correlationId resuelto>`
- `X-Request-Id: <correlationId resuelto>` *(El servicio ecoa el correlationId resuelto tanto en X-Correlation-Id como en X-Request-Id para garantizar consistencia unívoca de traza)*

### B. Payload de Entrada (Request)
La clave canónica de consulta se compone de 3 campos obligatorios (`lotteryCode`, `drawNumber`, `ticketNumber`). Los campos `series` y `checkDigit` se aceptan opcionalmente en el DTO para compatibilidad con el contrato general `TicketKey`:
```json
{
  "lotteryCode": "<codigo_loteria>",
  "drawNumber": "<sorteo>",
  "ticketNumber": "<real>"
}
```

> [!NOTE]
> **Pendiente DDL Oficial (Alex / Matthew):** La consulta SQL actual opera con la clave canónica de 3 campos (`lottery_code`, `draw_number`, `ticket_number`). Se solicitó a Alex/Matthew el DDL exacto de la tabla `LOTTERY_TICKETS` en Sybase DEV para confirmar si existen columnas físicas para `series` y `check_digit`. De existir en la tabla, se incorporarán a la cláusula `WHERE` del repositorio y a la respuesta; de no existir, se mantendrá la llave canónica de 3 campos y se sanearán los DTOs.

### C. Respuestas Tipadas Canónicas

#### 1. Boleto Ganador (Premiado - Caso 1 Oficial)
```json
{
  "found": true,
  "awarded": true,
  "ticketNumber": "<real>",
  "prize": {
    "amount": "<monto_confirmado>",
    "currency": "USD",
    "category": "CASH"
  },
  "collectionStatus": "<estado_fila_confirmado>",
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
  "collectionStatus": "<estado_fila_confirmado>",
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

#### 4. Fallo Seguro por Base de Datos no Disponible (HTTP 503 Service Unavailable)
Si el pool no está inicializado o la conexión física no responde:
```json
{
  "error": "DATABASE_UNAVAILABLE",
  "message": "Pool Sybase no disponible",
  "correlationId": "trace-fail-closed-001"
}
```

#### 5. Fallo de Integridad de Datos (HTTP 502 Bad Gateway)
Si los datos leídos de Sybase contienen inconsistencias de negocio:
```json
{
  "error": "DATA_INTEGRITY_ERROR",
  "message": "Inconsistencia de datos en Sybase: prize_amount tiene más de 2 decimales significativos...",
  "correlationId": "trace-integrity-001"
}
```

---

## 4. Diagnóstico y Salud del Pool JDBC

### Endpoint de Estado:
`GET /api/v1/tickets/status`

**Salida con Pool Sybase Activo:**
```json
{
  "poolActive": true,
  "databaseSource": "Sybase ASE LNB DEV",
  "seedEnabled": false,
  "lastError": null,
  "lastQueryError": null,
  "lastCheck": "2026-11-05T22:30:00Z"
}
```

**Salida en Entorno Offline (Sin VPN / Modo Fail-Closed por defecto):**
```json
{
  "poolActive": false,
  "databaseSource": "Ninguna (Pool no conectado)",
  "seedEnabled": false,
  "lastError": "Driver JDBC no encontrado... / Infraestructura Sybase DEV no alcanzable...",
  "lastQueryError": null,
  "lastCheck": "2026-11-05T22:30:00Z"
}
```

---

## 5. Clasificación y Estado de la Suite de Pruebas

La suite completa comprende **166 pruebas descubiertas**:
- **165 pruebas automatizadas (Unitarias y de Contrato) en VERDE (100% PASS):**
  - `SybaseAmountNormalizationTest` (20 invocaciones de prueba parametrizadas y unitarias): Valida normalización de montos (`50.0000` -> `50.00`, `50` -> `50.00`, `50.5` -> `50.50`), rechazo de escalas decimales con dígitos significativos (`50.005`), rechazo de montos `<= 0.00`, y rechazo de nulos/inválidos.
  - `TicketLookupControllerTest` (8 pruebas): Valida comportamiento 503 Fail-Closed saneado, 502 por error de integridad (`DATA_INTEGRITY_ERROR`), boleto ganador en modo seed, omisión de prize en no premiado, eco de `X-Correlation-Id`, endpoint `/status` con `lastError` y `lastQueryError`, y validación de `HTTP 400 INVALID_INPUT`.
  - `TicketAgentWithCarlosWsContractTest` (3 pruebas): Valida el contrato HTTP directo entre el agente de Steven y el WS de Carlos con shape canónico.
  - Pruebas del Worker, Governor, Pipeline y contratos generales (134 pruebas).
- **1 prueba de integración real (`SybaseTicketRealTest`):**
  - Anotada con `@EnabledIfEnvironmentVariable(named = "SYBASE_IT", matches = "true")`.
  - En CI/CD y entornos de desarrollo sin VPN a Sybase DEV, JUnit la reporta como **`SKIPPED`** (1 skipped) de forma segura y transparente, sin fallar el build.
  - **Estado actual:** Esta prueba **NO ha sido ejecutada contra la base de datos real** en este entorno local por falta de túnel VPN y driver con licencia comercial; está preparada y estructurada para ejecutarse en la VM institucional una vez configuradas las variables de entorno oficiales.

---

## 6. Instrucciones de Ejecución y Verificación

### A. Ejecución de suite de pruebas:
```powershell
# Ejecución estándar (165 pruebas en verde, prueba real skipped):
.\mvnw.cmd test

# Ejecución real en la VM con VPN y jconn4 instalado en .m2:
$env:SYBASE_IT = "true"
$env:APP_SYBASE_TICKET_URL = "jdbc:sybase:Tds:..."
$env:APP_SYBASE_TICKET_USERNAME = "..."
$env:APP_SYBASE_TICKET_PASSWORD = "..."
$env:SYBASE_REAL_TICKET = "<numero>"
$env:SYBASE_REAL_LOTTERY = "<codigo>"
$env:SYBASE_REAL_DRAW = "<sorteo>"
.\mvnw.cmd test -Psybase
```

### B. Ejecución del servicio localmente (Perfil Carlos):
```powershell
# En desarrollo offline (modo fail-closed por defecto):
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos

# En la VM con VPN y jconn4:
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=carlos -Psybase
```

### C. Verificación mediante script:
```powershell
# Modo desarrollo local:
.\scripts\verify-carlos-ws.ps1

# Modo estricto para corrida real con VPN:
.\scripts\verify-carlos-ws.ps1 -StrictMode -RealTicket <numero> -RealLottery <codigo> -RealDraw <sorteo>
```
