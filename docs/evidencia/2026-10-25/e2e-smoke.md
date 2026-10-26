# Evidencia — TicketAgent end-to-end con Vertex (2026-10-25)

**Cuenta:** steven@avanzatech.xyz
**Proyecto:** proy-comercial-dev-lnb (dev)
**Region:** global
**Modelo:** gemini-3.8-flash
**WS de tickets:** FIXTURE (sintetico)
**Entorno:** local, `mvnw spring-boot:run`

## Configuracion usada

```text
APP_TICKET_AGENT_MODEL_CLIENT=VERTEX
APP_VERTEX_PROJECT_ID=proy-comercial-dev-lnb
APP_TICKET_AGENT_REGION=global
APP_TICKET_AGENT_MODEL=gemini-3.8-flash
APP_TICKET_AGENT_MODE=FIXTURE
```

Log de arranque:

```text
TicketAgent VERTEX configurado: project=proy-comercial-dev-lnb, region=global, model=gemini-3.8-flash
Tomcat started on port 8080 (http)
Started WorkerPocApplication in 12.651 seconds
```

## Caso 1 — FOUND

Request:

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-001
Content-Type: application/json

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

Response (200):

```json
{
  "traceId": "smoke-001",
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "0012345678",
    "series": "A",
    "checkDigit": "5"
  },
  "result": "FOUND",
  "prize": {
    "amount": "150.00",
    "currency": "USD",
    "category": "CASH"
  },
  "collectionStatus": "PENDING",
  "provenance": "SYNTHETIC",
  "message": "Boleto encontrado y premiado"
}
```

## Caso 2 — NOT_AWARDED

Request:

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-002
Content-Type: application/json

{
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "0011111111"
  }
}
```

Response (200):

```json
{
  "traceId": "smoke-002",
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "0011111111",
    "series": null,
    "checkDigit": null
  },
  "result": "NOT_AWARDED",
  "prize": null,
  "collectionStatus": null,
  "provenance": "SYNTHETIC",
  "message": "Boleto no premiado"
}
```

## Caso 3 — NOT_FOUND

Request:

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-003
Content-Type: application/json

{
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "9999999999"
  }
}
```

Response (200):

```json
{
  "traceId": "smoke-003",
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "9999999999",
    "series": null,
    "checkDigit": null
  },
  "result": "NOT_FOUND",
  "prize": null,
  "collectionStatus": null,
  "provenance": "SYNTHETIC",
  "message": "Boleto no encontrado"
}
```

## Caso 4 — Error de entrada (400)

Request (sin `lotteryCode`):

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-004-invalid
Content-Type: application/json

{
  "ticket": {
    "drawNumber": "2026-10-15",
    "ticketNumber": "0012345678"
  }
}
```

Response (400):

```json
{
  "traceId": "30d3ea72-b5c1-4a64-99df-ba35f22f2382",
  "code": "INVALID_INPUT",
  "message": "Entrada invalida",
  "errors": [
    "ticket.lotteryCode: lotteryCode es obligatorio"
  ]
}
```

## Caso 5 — Timeout del WS (504)

Request:

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-005-timeout
Content-Type: application/json

{
  "ticket": {
    "lotteryCode": "LNB",
    "drawNumber": "2026-10-15",
    "ticketNumber": "TIMEOUT"
  }
}
```

Response (504):

```json
{
  "traceId": "e27c01c2-0613-4733-b57e-3424e0452475",
  "code": "WS_TIMEOUT",
  "message": "Timeout del WS de tickets"
}
```

El fixture `TIMEOUT` simula el timeout del WS de Carlos para validar el mapeo HTTP 504
sin depender del endpoint real.

## Resumen

| X-Request-Id | Caso | Resultado HTTP | Resultado | Prize |
|---|---|---|---|---|
| smoke-001 | encontrado | 200 | FOUND | 150.00 USD / CASH |
| smoke-002 | no premiado | 200 | NOT_AWARDED | null |
| smoke-003 | no encontrado | 200 | NOT_FOUND | null |
| smoke-004-invalid | entrada invalida | 400 | INVALID_INPUT | - |
| smoke-005-timeout | timeout del WS | 504 | WS_TIMEOUT | - |

## Hallazgo corregido durante la prueba

La primera version del prompt no incluia los datos del boleto, por lo que el modelo
no podia rellenar los argumentos de `consultar_boleto` y el agente respondia
`502 INVALID_RESPONSE`. El prompt ahora incluye `lotteryCode`, `drawNumber`,
`ticketNumber` y los opcionales `series`/`checkDigit` de forma explicita.

## Conclusion

- Vertex AI ejecuta `consultar_boleto` con `mode=ANY` sobre `gemini-3.8-flash` en `global`.
- `TicketAgent.verifyArgs` valida que el modelo no altere la clave.
- Las tres ramas del contrato (`FOUND`, `NOT_AWARDED`, `NOT_FOUND`) se responden desde el payload del WS.
- Los errores se separan de los estados de negocio: `400 INVALID_INPUT` y `504 WS_TIMEOUT` verificados por HTTP.
- No se usaron SQL/JDBC, datos reales ni el WS de produccion.
