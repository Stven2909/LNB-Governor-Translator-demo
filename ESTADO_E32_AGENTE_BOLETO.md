# Estado Actualizado — E3.2 Agente Consulta de Boleto

> **Actualizado:** 12/11/2026. Este documento describe el agente; el estado integrado del WS y
> del Caso 1 vive en `docs/evidencia/2026-11-06/` y en ClickUp (`[E1][CASO 1]`).

## 📌 Información Clave 24/10/2026

### Cambio de Prioridad (23/10/2026 - Jorge Daniel)
- **NUEVA PRIORIDAD:** Agente de Consulta de Boleto (E3.2)
- **ANTES:** Gobernador financiero con Vertex AI
- **AHORA:** Steven construye agente que consume WS de Carlos

### Confimación Reciente
> "Si, confirmo todo, ahorita Carlos creo que no ha hecho nada de su parte, asi que avancemos con tu propuesta" - Steven a JD 24/10

---

## ✅ Estado E3.2 — IMPLEMENTADO

**Endpoint:** `POST /api/v1/agent/ticket-query`

**Contrato entregado y verificado con el WS de Carlos (`04e33c4`):**

**Entrada:**
```json
{
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15", 
    "ticketNumber": "0012345678",
    "series": "A",
    "checkDigit": "5"
  }
}
```

**Salida:**
```json
{
  "traceId": "uuid",
  "ticket": {...},
  "result": "FOUND|NOT_FOUND|NOT_AWARDED|ERROR",
  "prize": {"amount": "150.00", "currency": "USD", "category": "CASH"},
  "collectionStatus": "PENDING",
  "provenance": "SYNTHETIC",
  "message": "Boleto encontrado y premiado correctamente"
}
```

---

## 📁 Archivos Creados (D1 - 27/10)

**Contracts (`src/main/java/.../contract/ticket/`):**
- `TicketKey.java` - Clave tipada
- `TicketQueryRequest.java` - Wrapper entrada
- `TicketQueryResult.java` - Resultado
- `TicketLookupStatus.java` - Enum estado
- `TicketProvenance.java` - REAL|SYNTHETIC
- `PrizeInfo.java` - Info premio

**Agente (`src/main/java/.../agent/`):**
- `TicketAgent.java` - Lógica Vertex AI + function calling
- `TicketAgentController.java` - Endpoint REST
- `TicketAgentException.java` - Manejo errores

**Herramientas (`src/main/java/.../agent/tool/`):**
- `TicketQueryTool.java` - Interfaz
- `FixtureTicketQueryTool.java` - Mock FIXTURE mode
- `HttpTicketQueryTool.java` - HTTP real
- `TicketAgentProperties.java` - Config

**Recursos:**
- `/prompts/ticket-agent-v0.1.txt` - Prompt
- `/fixtures/tickets/found.json` - Fixture premiado
- `/fixtures/tickets/not-found.json` - Fixture no encontrado
- `/fixtures/tickets/not-awarded.json` - Fixture no premiado
- `/fixtures/tickets/timeout.json` - Fixture timeout
- `scripts/ticket-query.ps1` - Runner

---

## 🧪 Matriz de Pruebas (6 casos + 6 invalidos)

| Caso | Entrada | Esperado | Estado |
|------|---------|----------|--------|
| found | lotteryCode=LNB, drawNumber=2026-10-15, ticketNumber=0012345678 | FOUND, prize | ✅ |
| not-found | ticket 9999999999 | NOT_FOUND, sin prize | ✅ |
| not-awarded | ticket 0011111111 | NOT_AWARDED, sin prize | ✅ |
| invalid | faltan campos obligatorios | 400, INVALID_INPUT | ✅ |
| timeout | WS no responde | 504, TIMEOUT | ✅ |
| tool-no-invoked | modelo no llama tool | 502, INVALID_RESPONSE | ✅ |

---

## 🔧 Configuración

```properties
APP_TICKET_AGENT_MODE=FIXTURE|HTTP
APP_TICKET_AGENT_MODEL_CLIENT=FAKE|VERTEX
APP_TICKET_AGENT_MODEL=gemini-3.8-flash
APP_TICKET_AGENT_REGION=global
APP_TICKET_AGENT_BASE_URL=http://localhost:8080
APP_TICKET_AGENT_LOOKUP_PATH=/api/v1/tickets/lookup
APP_TICKET_AGENT_TIMEOUT=10s
APP_TICKET_AGENT_PROVENANCE=SYNTHETIC
```

---

## ⚠️ Bloqueo Actual

### Modelo Vertex — DESBLOQUEADO
- `gemini-2.5-flash` venció el 20/10/2026.
- **Seleccionado:** `gemini-3.8-flash` como `APP_TICKET_AGENT_MODEL`.
- **Verificado el 25/10/2026:** `us-central1` devuelve `404 NOT_FOUND`; `locations/global` devuelve `200`. `APP_TICKET_AGENT_REGION=global` por defecto.
- **Function calling verificado:** con `toolConfig.mode=ANY`, el modelo devuelve `consultar_boleto` con `lotteryCode`, `drawNumber` y `ticketNumber` correctos.
- Evidencia: `docs/evidencia/2026-10-25/vertex-function-call.md`.
- **End-to-end verificado el 25/10/2026:** `POST /api/v1/agent/ticket-query` devolvió `FOUND` con `prize.amount=150.00`, `provenance=SYNTHETIC` y `traceId=smoke-001`, usando Gemini real + WS fixture. También se verificaron `NOT_AWARDED`, `NOT_FOUND`, `400 INVALID_INPUT` y `504 WS_TIMEOUT`. Evidencia: `docs/evidencia/2026-10-25/e2e-smoke.md`.
- **WS de Carlos:** entregado en `feat/e1-carlos-ticket-ws` @ `04e33c4` (ver §WS de Carlos). Lo
  pendiente no es el código ni el contrato, sino su publicación y la lectura física de Sybase.

### Alcance y producción
- Todas las pruebas apuntan a `proy-comercial-dev-lnb` (proyecto **dev**), no a un proyecto productivo.
- `generateContent` es stateless: no escribe en Sybase, no llama al WS real y no modifica datos. El único efecto es consumo de cuota y logs de auditoría en el proyecto dev.
- Los boletos usados son fixtures SINTÉTICOS (sin datos reales de ciudadanos).
- **Antes de producción:** `locations/global` implica procesamiento fuera de `us-central1`; confirmar residencia de datos con Cloudfly/seguridad y evaluar un modelo disponible en la región. Además, usar cuenta de servicio con `roles/aiplatform.user` y alertas de presupuesto.

### WS de Carlos
- **Estado:** Código y contrato entregados en `feat/e1-carlos-ticket-ws` @ `04e33c4` (integrado en `feat/e3.2-integracion` @ `8f9b844`).
- **Verificación integrada:** Probado en enlace HTTP local mediante `scripts/verify-agent-e2e.ps1` con 6 consultas efectivas en log y discriminación fail-closed (`scripts/verify-agent-failclosed.ps1` -> 502 `WS_ERROR`).
- **Pendiente de despliegue:** Publicación e infraestructura en Cloud Run / API Gateway (Kimi/Luis) y lectura física real contra Sybase DEV (Alex/IT LNB con driver `jconn4` y credenciales).

---

## 🚀 Próximos Pasos

1. ✅ **D1 (27/10):** Contrato + fixtures + tool + tests — COMPLETADO
2. ✅ **D2:** Suite de integración — 193 descubiertos, 192 verdes, 1 omitido (06/11/2026)
3. ⏳ **D3:** Publicación en Cloud Run y lectura real del WS — depende de infraestructura y Sybase
4. ⏳ **Cierre:** solo en el hito `86bd24hhg` cuando Equipo 2 reciba la misma clave con `REAL/SYBASE`

---

## 📞 Comunicación Clave

**JD a Carlos/Steven/Henry:**
> "retomemos el contrato con las decisiones ya registradas el 14/10: claimId se conserva; instrumentIds es obligatorio..."

**Steven a JD (24/10):**
> "Si, confirmo todo, ahorita Carlos creo que no ha hecho nada de su parte, asi que avancemos con tu propuesta"

**Acción:** Proceed with contract v0.1 proposed by Steven for WS de boleto de Carlos