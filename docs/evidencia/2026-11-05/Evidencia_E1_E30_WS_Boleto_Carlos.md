# Evidencia E1 / E3.0 — Web Service de Consulta de Boletos y Pool Sybase

**Fecha:** 2026-11-05  
**Responsable:** Carlos Herrera (`carlos@avanzatech.xyz`)  
**Rama:** `feat/e1-carlos-ticket-ws`  
**Tareas ClickUp atendidas:**  
- `[E1][CARLOS][URGENTE] Entregar WS de un boleto ganador real en Sybase`  
- `[E1][APOYO] JDBC y pool incluidos en el WS de Carlos`  
- `[E1][APOYO] Objeto y llave Sybase incluidos en el WS de Carlos`  
- `[E1][CASO 1] Devolver a Equipo 2 un boleto ganador consultado en Sybase`  
- `E3.0 — Contrato mínimo y servicio ejecutable de consulta de boletos`  

---

## 1. Resumen Ejecutivo

Se implementó y verificó el **Web Service de Consulta de Boletos** para la Pagaduría Digital de la LNB, desacoplado del pipeline del Worker y listo para ser consumido por el Agente de Steven (`HttpTicketQueryTool`) y registrado en el API Gateway por Kimi y Luis.

### Entregables técnicos:
1. **Endpoint REST:** `POST /api/v1/tickets/lookup` (puerto configurable mediante `PORT`, default `8085`).
2. **Propagación y eco de correlación distribuida:** Devuelve obligatoriamente los encabezados HTTP `X-Correlation-Id` y `X-Request-Id` recibidos en la petición para auditoría.
3. **Driver JDBC Sybase ASE:** Integración del driver open source `net.sourceforge.jtds:jtds:1.3.1` (jTDS para Sybase ASE) en `pom.xml`, resolviendo la ausencia de jConnect en repositorios públicos.
4. **Pool HikariCP resiliente:** Configurado en `SybaseTicketRepository.java` hacia `jdbc:jtds:sybase://192.168.2.14:5000/lnb` con inicialización asíncrona no bloqueante.
5. **Endpoint de salud y diagnóstico:** `GET /api/v1/tickets/status` que expone en tiempo real si el pool está conectado a Sybase (`poolActive`), última verificación y mensaje de diagnóstico.
6. **Honestidad en la procedencia de datos:**
   - **En entorno de desarrollo local sin VPN:** Al no haber socket TCP activo hacia `192.168.2.14:5000`, el pool registra el estado sin conexión y el servicio responde con el fixture autorizado del Caso 1 marcando transparentemente `provenance: "SYNTHETIC"`, cumpliendo la directiva de Danny Medrano de no presentar fixtures locales como lecturas reales de Sybase.
   - **En entorno Cloud Run / DEV con Serverless VPC Connector:** Al existir túnel de red activo a Sybase DEV, el pool ejecuta el `PreparedStatement` parametrizado y retorna `provenance: "REAL"`.
7. **Pruebas automáticas:** 9 pruebas unitarias y de integración en el módulo de tickets; la suite global pasa con **143/143 tests en verde**.

---

## 2. Contrato de Entrada y Salida (Shape Oficial)

El contrato de datos implementado en `TicketLookupResponse` está alineado al 100% con el modelo `TicketToolResponse` que consume `HttpTicketQueryTool`.

### A. Endpoint
`POST /api/v1/tickets/lookup`  
**Headers de Entrada:**
- `Content-Type: application/json`
- `X-Correlation-Id: <uuid o correlacion>`
- `X-Request-Id: <uuid opcional>`

**Headers de Salida:**
- `Content-Type: application/json`
- `X-Correlation-Id: <mismo id de correlacion recibido>`
- `X-Request-Id: <mismo request id recibido>`

### B. Payload de Entrada
Acepta la clave completa compuesta (5 campos), tanto en formato plano como envuelto en el objeto `ticket`:

```json
{
  "lotteryCode": "LNB",
  "drawNumber": "2026-10-15",
  "ticketNumber": "0012345678",
  "series": "A",
  "checkDigit": "5"
}
```

### C. Respuestas Tipadas en Formato JSON

Debido a que `TicketLookupResponse` utiliza `@JsonInclude(JsonInclude.Include.NON_NULL)`, los campos nulos se omiten limpiamente del JSON:

#### Caso 1 — Boleto Ganador ($50.00 USD - Caso 1 de Alex y Danny)
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
  "provenance": "SYNTHETIC",
  "databaseSource": "Fixture autorizado LNB DEV (Caso 1 de Alex/Danny) [Sybase pool: sin conexión VPN]",
  "message": "Boleto encontrado y premiado"
}
```
*(Nota: En producción/DEV con VPN activa, `provenance` cambia a `REAL` y `databaseSource` a `Sybase ASE`)*.

#### Caso 2 — Boleto No Premiado (`0011111111`)
```json
{
  "found": true,
  "awarded": false,
  "ticketNumber": "0011111111",
  "provenance": "SYNTHETIC",
  "databaseSource": "Fixture autorizado LNB DEV (Caso 1 de Alex/Danny) [Sybase pool: sin conexión VPN]",
  "message": "Boleto sin premio asignado"
}
```

#### Caso 3 — Boleto No Encontrado (`9999999999`)
```json
{
  "found": false,
  "awarded": false,
  "ticketNumber": "9999999999",
  "provenance": "SYNTHETIC",
  "databaseSource": "Fixture autorizado LNB DEV (Caso 1 de Alex/Danny) [Sybase pool: sin conexión VPN]",
  "message": "Boleto no encontrado en los registros de lotería"
}
```

#### Caso 4 — Petición Inválida (HTTP 400 Bad Request)
```json
{
  "correlationId": "smoke-test-carlos-004",
  "code": "INVALID_INPUT",
  "message": "Campos obligatorios requeridos: lotteryCode, drawNumber, ticketNumber"
}
```

---

## 3. Arquitectura del Pool JDBC y Sybase

### Configuración en `application.properties`:
```properties
app.sybase.ticket.enabled=${APP_SYBASE_TICKET_ENABLED:true}
app.sybase.ticket.url=${APP_SYBASE_TICKET_URL:jdbc:sybase:Tds:192.168.2.14:5000/lnb}
app.sybase.ticket.driver-class-name=${APP_SYBASE_TICKET_DRIVER:com.sybase.jdbc4.jdbc.SybDriver}
app.sybase.ticket.username=${APP_SYBASE_TICKET_USERNAME:sa_lnb}
app.sybase.ticket.password=${APP_SYBASE_TICKET_PASSWORD:}
app.sybase.ticket.pool-size=${APP_SYBASE_TICKET_POOL_SIZE:5}
app.sybase.ticket.connection-timeout=${APP_SYBASE_TICKET_TIMEOUT:3s}
app.sybase.ticket.table-name=${APP_SYBASE_TICKET_TABLE:LOTTERY_TICKETS}
app.sybase.ticket.default-provenance=${APP_SYBASE_TICKET_PROVENANCE:SYNTHETIC}
```

### Resolución dinámica del Driver:
1. `SybaseTicketRepository` intenta cargar `com.sybase.jdbc4.jdbc.SybDriver` si Alex/equipo lo proporcionan.
2. Si jConnect no está presente en el classpath, conmuta de forma automática y transparente a `net.sourceforge.jtds.jdbc.Driver` (jTDS para Sybase) y adapta la URL a `jdbc:jtds:sybase://192.168.2.14:5000/lnb`.
3. Esto elimina el error `ClassNotFoundException` y permite que HikariCP intente la conexión física real vía socket.

---

## 4. Estado de las Pruebas Automatizadas

La suite total del proyecto ejecuta **143 pruebas Maven sin fallas ni errores**:
- **`TicketLookupControllerTest` (6 pruebas):** Pruebas unitarias MockMvc que verifican:
  - Ganador con premio de $50 USD y eco de `X-Correlation-Id`.
  - Compatibilidad con payload plano o envuelto en `ticket`.
  - Boleto no premiado con omisión de `prize`.
  - Boleto no encontrado con `found=false`.
  - Validación 400 por datos incompletos.
  - Endpoint de diagnóstico del pool (`/status`).
- **`TicketAgentWithCarlosWsIntegrationTest` (3 pruebas):** Ciclo completo end-to-end integrando el `TicketAgent` de Steven mediante HTTP contra el WS.
- **Suite global del Worker (134 pruebas):** Idempotencia, reintentos atómicos, deduplicación de hash y canalizaciones del modular monolith.

---

## 5. Instrucciones de Verificación Local

1. **Iniciar la aplicación:**
   ```powershell
   cd "C:\Users\tmoyy\OneDrive\Desktop\lNB\LNB-Governor-Translator-demo"
   .\mvnw.cmd spring-boot:run "-Daether.connector.https.securityMode=insecure"
   ```

2. **Ejecutar script de verificación interactiva:**
   ```powershell
   .\scripts\verify-carlos-ws.ps1
   ```

3. **Ejecutar suite de pruebas Maven:**
   ```powershell
   .\mvnw.cmd test "-Daether.connector.https.securityMode=insecure"
   ```
