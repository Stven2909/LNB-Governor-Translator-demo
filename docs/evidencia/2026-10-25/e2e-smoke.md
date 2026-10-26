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

## Resumen

| X-Request-Id | Boleto | Resultado | Prize | Provenance |
|---|---|---|---|---|
| smoke-001 | 0012345678 | FOUND | 150.00 USD / CASH | SYNTHETIC |
| smoke-002 | 0011111111 | NOT_AWARDED | null | SYNTHETIC |
| smoke-003 | 9999999999 | NOT_FOUND | null | SYNTHETIC |

## Hallazgo corregido durante la prueba

La primera version del prompt no incluia los datos del boleto, por lo que el modelo
no podia rellenar los argumentos de `consultar_boleto` y el agente respondia
`502 INVALID_RESPONSE`. El prompt ahora incluye `lotteryCode`, `drawNumber`,
`ticketNumber` y los opcionales `series`/`checkDigit` de forma explicita.

## Conclusion

- Vertex AI ejecuta `consultar_boleto` con `mode=ANY` sobre `gemini-3.8-flash` en `global`.
- `TicketAgent.verifyArgs` valida que el modelo no altere la clave.
- Las tres ramas del contrato (`FOUND`, `NOT_AWARDED`, `NOT_FOUND`) se responden desde el payload del WS.
- No se usaron SQL/JDBC, datos reales ni el WS de produccion.
