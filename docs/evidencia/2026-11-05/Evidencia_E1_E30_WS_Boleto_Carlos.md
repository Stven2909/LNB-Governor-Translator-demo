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
1. **Contrato Canónico Cerrado:** Se consolidó el contrato oficial de `TicketLookupResponse` con shape estructurado (`found`, `awarded`, `ticketNumber`, `prize{amount, currency, category}`, `collectionStatus`, `provenance`, `source`, `databaseSource`, `message`). El JSON plano preliminar queda formalmente retirado.
2. **Esquema Fail-Closed (Fallo Seguro):** Si el pool de Sybase no está activo o la consulta a la base de datos falla, el servicio responde con **`HTTP 503 Service Unavailable`** con error tipado `DATABASE_UNAVAILABLE`. El modo semilla (`resolveAuthorizedSeed`) queda desactivado por defecto (`app.sybase.ticket.seed-enabled=false`) y solo se activa explícitamente en el perfil local de desarrollo.
3. **Honestidad Rigurosa en la Procedencia:**
   - `provenance: "REAL"` y `source: "SYBASE"` se emiten **únicamente** cuando los datos provienen de una fila física (`ResultSet.next()`) en Sybase ASE con `poolActive: true`.
   - Cuando se activa el perfil de desarrollo offline sin conexión VPN a Sybase DEV (`192.168.2.14:5000`), el servicio declara con transparencia **`provenance: "SYNTHETIC"`** y **`source: "SEED"`**. Nunca se afirma procedencia real sin conexión física.
4. **Driver Sybase Institucional (jConnect):**
   - El driver corporativo oficial es `com.sybase.jdbc4.jdbc.SybDriver` (`jconn4.jar`).
   - Debido a restricciones de licenciamiento de SAP/Sybase, el binario `.jar` no se commitea en repositorios públicos; se instala en el repositorio Maven local o privado institucional con el comando:
     ```bash
     mvn install:install-file -Dfile=jconn4.jar -DgroupId=com.sybase.jdbc4 -DartifactId=jconn4 -Dversion=16.0 -Dpackaging=jar
     ```
   - El repositorio cuenta con detección automática para cargar `jconn4.jar` en cuanto esté disponible en el classpath.
5. **Configuración Limpia y Aislada:**
   - Sin IP ni credenciales hardcodeadas en código; se resuelven mediante variables de entorno y Secret Manager.
   - `app.sybase.ticket.enabled=false` por defecto globalmente para no afectar builds automáticos de CI/CD.
   - El puerto por defecto del Worker permanece en `PORT:8080` y la configuración de Carlos se activa limpiamente mediante el perfil de Spring Boot: `application-carlos.properties`.
   - No se aplican exclusiones globales a `DataSourceAutoConfiguration`, manteniendo intacto el entrypoint del Worker (`WorkerPocApplication.java`).
6. **Correlación Distribuida:** El servicio captura `X-Correlation-Id` y `X-Request-Id` y los devuelve obligatoriamente en las cabeceras HTTP de respuesta y en los registros de log.

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
Acepta la clave compuesta del boleto (con `drawNumber` como String):
```json
{
  "lotteryCode": "LNB",
  "drawNumber": "2026-10-15",
  "ticketNumber": "0012345678",
  "series": "A",
  "checkDigit": "5"
}
```

### C. Respuestas Tipadas Canónicas

#### 1. Boleto Ganador ($50.00 USD)
```json
{
  "found": true,
  "awarded": true,
  "ticketNumber": "0012345678",
  "prize": {
    "amount": "50.00",
    "currency": "USD",
    "category": "CASH"
  },
  "collectionStatus": "PENDING",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE (jdbc:sybase:Tds:192.168.2.14:5000/lnb)",
  "message": "Boleto encontrado y premiado"
}
```
*(En entorno offline de pruebas sin VPN, `provenance` reporta honestamente `"SYNTHETIC"` y `source` `"SEED"`)*.

#### 2. Boleto No Premiado
```json
{
  "found": true,
  "awarded": false,
  "ticketNumber": "0011111111",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE (jdbc:sybase:Tds:192.168.2.14:5000/lnb)",
  "message": "Boleto sin premio asignado"
}
```
*(Debido a `@JsonInclude(NON_NULL)`, el campo `prize` se omite limpiamente)*.

#### 3. Boleto No Encontrado
```json
{
  "found": false,
  "awarded": false,
  "ticketNumber": "9999999999",
  "provenance": "REAL",
  "source": "SYBASE",
  "databaseSource": "Sybase ASE (jdbc:sybase:Tds:192.168.2.14:5000/lnb)",
  "message": "Boleto no encontrado"
}
```

#### 4. Fallo Seguro (HTTP 503 Service Unavailable)
Si el pool no está activo y la semilla está deshabilitada:
```json
{
  "error": "DATABASE_UNAVAILABLE",
  "message": "Pool Sybase no disponible (poolActive=false)...",
  "correlationId": "trace-fail-closed-001"
}
```

---

## 3. Arquitectura y Salud del Pool JDBC

### Endpoint de Diagnóstico:
`GET /api/v1/tickets/status`

**Salida con Sybase activo:**
```json
{
  "poolActive": true,
  "lastError": null,
  "lastCheck": "2026-11-05T22:30:00Z"
}
```

---

## 4. Clasificación y Estado de las Pruebas

Para mantener total rigor metodológico, las pruebas se clasifican y ejecutan en categorías diferenciadas:

1. **`TicketLookupControllerTest` (7 pruebas MockMvc):**
   - Valida el comportamiento Fail-Closed devolviendo `HTTP 503` cuando el pool no está activo y `seed-enabled=false`.
   - Valida el boleto ganador en modo pruebas con `provenance: "SYNTHETIC"` y `source: "SEED"`.
   - Valida la propagación y eco de `X-Correlation-Id`.
   - Valida la omisión de `prize` en boletos no premiados.
   - Valida boletos inexistentes (`found=false`) y rechazo de peticiones incompletas (`HTTP 400`).
2. **`TicketAgentWithCarlosWsContractTest` (3 pruebas de contrato local):**
   - Prueba de contrato local entre el `TicketAgent` de Steven (usando el cliente determinista `DeterministicTicketModelClient`) y el WS de Carlos vía HTTP. Acredita que el cliente HTTP de Steven deserializa sin errores el shape oficial.
3. **`SybaseTicketRealIT` (Prueba de integración física gateada):**
   - Anotada con `@EnabledIfEnvironmentVariable(named = "SYBASE_IT", matches = "true")`.
   - Solo se ejecuta en entornos con conectividad de red y túnel VPN hacia `192.168.2.14:5000`. En cualquier otro entorno, JUnit la salta (`SKIPPED`) de forma transparente sin romper el pipeline.

---

## 5. Instrucciones de Ejecución y Verificación

### A. Para desarrollo local y pruebas (Perfil Carlos):
```powershell
cd "C:\Users\tmoyy\OneDrive\Desktop\lNB\LNB-Governor-Translator-demo"
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=carlos" "-Daether.connector.https.securityMode=insecure"
```

### B. Ejecutar script de verificación:
```powershell
# En modo desarrollo local:
.\scripts\verify-carlos-ws.ps1

# En modo estricto para corrida real con VPN:
.\scripts\verify-carlos-ws.ps1 -StrictMode
```

### C. Ejecutar suite de pruebas:
```powershell
.\mvnw.cmd test "-Daether.connector.https.securityMode=insecure"
```
*(Para activar la prueba de integración real cuando haya VPN: `$env:SYBASE_IT="true"; .\mvnw.cmd test`)*.
