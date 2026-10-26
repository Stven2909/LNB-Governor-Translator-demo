# Agente de Consulta de Boleto — E3.2

**Estado:** Implementado (D1/D2 planificados)  
**Fecha:** 24 octubre 2026  
**Responsable:** Steven Rivera

---

## 1. Visión del Agente

El agente de consulta de boleto es un servicio autónomo que recibe solicitudes de consulta de boleto, extrae los parámetros, llama a la herramienta de consulta y devuelve el resultado. El modelo **NUNCA** inventa premio/monto.

---

## 2. Archivos creados

### Contratos
- `TicketKey.java` - Clave tipada del boleto
- `TicketQueryRequest.java` - Wrapper de entrada
- `TicketQueryResult.java` - Resultado de la consulta
- `TicketLookupStatus.java` - Estado: FOUND | NOT_FOUND | NOT_AWARDED | ERROR | INVALID_INPUT | TIMEOUT
- `TicketProvenance.java` - REAL | SYNTHETIC
- `PrizeInfo.java` - Información del premio

### Agente
- `TicketAgent.java` - Lógica principal con function calling
- `TicketAgentController.java` - Endpoint REST POST /api/v1/agent/ticket-query
- `TicketAgentException.java` - Manejo de errores
- `TicketQueryTool.java` - Interfaz de la herramienta

### Herramientas
- `FixtureTicketQueryTool.java` - Mock para pruebas (modo FIXTURE)
- `HttpTicketQueryTool.java` - Cliente real HTTP (modo HTTP)
- `TicketAgentProperties.java` - Configuración

### Recursos
- `/prompts/ticket-agent-v0.1.txt` - Prompt del modelo
- `/fixtures/tickets/found.json` - Fixture: boleto premiado
- `/fixtures/tickets/not-found.json` - Fixture: boleto no encontrado
- `/fixtures/tickets/not-awarded.json` - Fixture: boleto no premiado
- `/fixtures/tickets/timeout.json` - Fixture: timeout
- `scripts/ticket-query.ps1` - Runner de pruebas

---

## 3. Endpoint

```
POST /api/v1/agent/ticket-query
Header: X-Request-Id (opcional)
Body:
{
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "0012345678",
    "series": "A", (opcional)
    "checkDigit": "5" (opcional)
  }
}
```

**Respuesta 200:**
```json
{
  "traceId": "uuid",
  "ticket": {...},
  "result": "FOUND|NOT_FOUND|NOT_AWARDED|ERROR",
  "prize": {"amount": "150.00", "currency": "USD", "category": "CASH"},
  "collectionStatus": "APROBADO",
  "provenance": "SYNTHETIC",
  "message": "Boleto encontrado y premiado correctamente"
}
```

**Respuesta 400:**
```json
{
  "result": "INVALID_INPUT",
  "message": "Error de validación"
}
```

---

## 4. Matriz de pruebas

| Caso | Esperado | Status |
|------|----------|--------|
| found | FOUND, prize, traceId | ✅ |
| not-found | NOT_FOUND, sin prize | ✅ |
| not-awarded | NOT_AWARDED, sin prize | ✅ |
| invalid | 400, INVALID_INPUT | ✅ |
| timeout | TIMEOUT/ERROR | ✅ |
| tool no invoked | INVALID_RESPONSE | ✅ |
| args alterados | INVALID_RESPONSE | ✅ |

---

## 5. Configuración

```properties
# Modo del agente
app.ticket-agent.mode=FIXTURE|HTTP

# WS de Carlos (solo HTTP)
app.ticket-agent.base-url=http://localhost:8080
app.ticket-agent.lookup-path=/api/v1/tickets/lookup
app.ticket-agent.timeout=10s
app.ticket-agent.provenance=SYNTHETIC
```

---

## 6. Próximos pasos

1. ✅ D1: Contrato + fixtures + tool + tests (27/10) - COMPLETADO
2. ⏳ D2: Agente con tool-calling + suite verde (29/10)
3. 🔴 D3: Llamada real al WS de Carlos + modelo verificado

---

## 7. Bloqueo actual

- **Modelo Vertex:** `gemini-2.5-flash` venció 20/10. Model Garden confirma `gemini-3.8-flash` como Flash vigente; seleccionado vía `APP_TICKET_AGENT_MODEL`. Verificado el 25/10/2026: `us-central1` responde `404 NOT_FOUND`, mientras `locations/global` responde `200` con `modelVersion: gemini-3.8-flash`; por eso `APP_TICKET_AGENT_REGION=global`. Function calling y flujo end-to-end verificados: `POST /api/v1/agent/ticket-query` devolvió `FOUND` con `prize.amount=150.00` y `provenance=SYNTHETIC`. Evidencia en `docs/evidencia/2026-10-25/`. Pendiente: WS real de Carlos (modo `HTTP`).
- **WS de Carlos:** No publicado. Avanzamos con contrato v0.1 y fixtures SINTÉTICOS