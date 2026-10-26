# Evidencia E3.2 — Agente de boleto (25/10/2026)

**Responsable:** Steven Rivera
**Rama:** `feat/e3.2-ticket-agent`
**Commits:** `1d448c9` (implementacion) + `229f3cb` (evidencia de errores)
**Base:** `0302188` (antecedente Governor, intacto)

## Entorno de la prueba

| Item | Valor |
|---|---|
| Proyecto GCP | `proy-comercial-dev-lnb` (dev) |
| Cuenta | `steven@avanzatech.xyz` |
| Modelo | `gemini-3.8-flash` |
| Region | `global` |
| WS de tickets | `FIXTURE` (sintetico) |
| Ejecucion | local, `mvnw spring-boot:run` |

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

## 1. Function calling verificado contra Vertex AI

El modelo recibe la herramienta `consultar_boleto` con `toolConfig.mode=ANY` y responde:

```json
{
  "functionCall": {
    "name": "consultar_boleto",
    "args": {
      "lotteryCode": "LNB",
      "ticketNumber": "0012345678",
      "drawNumber": "2026-10-15"
    },
    "id": "call_3638091"
  }
}
```

- `modelVersion`: `gemini-3.8-flash`
- `finishReason`: `STOP`
- `locations/global` responde `200`; `us-central1` responde `404` para este modelo/proyecto.

## 2. Casos HTTP ejecutados

### Caso 1 — FOUND

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-001
{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678","series":"A","checkDigit":"5"}}
```

```json
{
  "traceId": "smoke-001",
  "result": "FOUND",
  "prize": { "amount": "150.00", "currency": "USD", "category": "CASH" },
  "collectionStatus": "PENDING",
  "provenance": "SYNTHETIC",
  "message": "Boleto encontrado y premiado"
}
```

### Caso 2 — NOT_AWARDED

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-002
{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0011111111"}}
```

```json
{
  "traceId": "smoke-002",
  "result": "NOT_AWARDED",
  "prize": null,
  "collectionStatus": null,
  "provenance": "SYNTHETIC",
  "message": "Boleto no premiado"
}
```

### Caso 3 — NOT_FOUND

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-003
{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"9999999999"}}
```

```json
{
  "traceId": "smoke-003",
  "result": "NOT_FOUND",
  "prize": null,
  "collectionStatus": null,
  "provenance": "SYNTHETIC",
  "message": "Boleto no encontrado"
}
```

### Caso 4 — Error de entrada (400)

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-004-invalid
{"ticket":{"drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
```

```json
{
  "traceId": "30d3ea72-b5c1-4a64-99df-ba35f22f2382",
  "code": "INVALID_INPUT",
  "message": "Entrada invalida",
  "errors": ["ticket.lotteryCode: lotteryCode es obligatorio"]
}
```

### Caso 5 — Timeout del WS (504)

```http
POST /api/v1/agent/ticket-query
X-Request-Id: smoke-005-timeout
{"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"TIMEOUT"}}
```

```json
{
  "traceId": "e27c01c2-0613-4733-b57e-3424e0452475",
  "code": "WS_TIMEOUT",
  "message": "Timeout del WS de tickets"
}
```

### Resumen

| X-Request-Id | Caso | HTTP | Resultado |
|---|---|---|---|
| smoke-001 | encontrado | 200 | FOUND — 150.00 USD / CASH |
| smoke-002 | no premiado | 200 | NOT_AWARDED |
| smoke-003 | no encontrado | 200 | NOT_FOUND |
| smoke-004-invalid | entrada invalida | 400 | INVALID_INPUT |
| smoke-005-timeout | timeout WS | 504 | WS_TIMEOUT |

## 3. Suite automatizada

```text
Tests run: 102, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Incluye 8 tests nuevos del agente (FOUND, NOT_FOUND, NOT_AWARDED, sin function call,
argumentos alterados, timeout e inconsistencia del WS). Los 94 tests base del commit
`0302188` se conservan.

## 4. Hallazgos

- `gemini-2.5-flash` esta retirado desde el 20/10/2026.
- `gemini-3.8-flash` solo esta disponible en `locations/global` para este proyecto.
- Se corrigio un caso donde el prompt no incluia los datos del boleto y producia
  `502 INVALID_RESPONSE`; ahora el prompt envia la clave completa.

## 5. Alcance y seguridad

- Proyecto dev, datos sinteticos.
- Sin SQL/JDBC, sin Sybase, sin WS real y sin datos de produccion.
- El premio/monto proviene unicamente del payload del WS; el agente no lo calcula ni lo inventa.

## 6. Pendiente externo

1. WS de Carlos: publicacion, verbo/path, contrato exacto y semantica de `NOT_AWARDED`.
2. Confirmacion formal de modelo/region/cuota con Cloudfly (JD11).
3. Aprobacion de residencia de datos para `locations/global`.
4. Cuenta de servicio, despliegue en Cloud Run y credenciales productivas.
5. Vincular esta evidencia en la tarea de Despliegue de Governor/Translator.
