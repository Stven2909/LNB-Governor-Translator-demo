# CONTRACT_SYNTHETIC_V0
**Estado:** Activo para PoC técnica — desacoplado de Alex/LNB
**Versión:** CONTRACT_SYNTHETIC_V0 (futura: CONTRACT_REAL_V1 tras respuesta de Alex)
**Fecha:** 07 septiembre 2026
**Responsables:** Carlos, Henry, Steven
**Base:** `GOVERNOR_CONTRACT_V1.1-2.docx` + `Propuesta_Vertical_v2.pdf` + Feedback Técnico Integrado

> **Regla JD:** Con herramientas de IA no se espera a Alex para validar plataforma. Este contrato sintético es el insumo para la prueba vertical con fixture DEV.

---

## 1. Evento que recibe el Worker desde Pub/Sub Push (Envelope)

El Worker recibe un mensaje HTTP POST desde Pub/Sub Push con el siguiente envelope estándar (Base64):

```json
{
  "subscription": "projects/dulcet-listener-505916-n5/subscriptions/synthetic-payment-sub",
  "message": {
    "data": "ewogICJjb250cmFjdF92ZXJzaW9uIjogIkNPTlRSQUNUX1NZTlRIRVRJQ19WMCIsCiAgImV2ZW50SWQiOiAiZXZ0LTAwMSIsCiAgImV2ZW50VHlwZSI6ICJTWU5USEVUSUNfUEFZTUVOVF9SRVFVRVNURUQiLAogICJvcGVyYXRpb25JZCI6ICJvcC0wMDEiLAogICJ0cmFjZUlkIjogInRyYWNlLTAwMSIsCiAgIm9jY3VycmVkQXQiOiAiMjAyNi0wOS0wN1QwMDowMDowMFoiLAogICJvcGVyYXRpb24iOiAiSU5TRVJUIiwKICAiZW50aXR5IjogInN5bnRoZXRpY19wYXltZW50IiwKICAicGF5bG9hZCI6IHsKICAgICJjbGFpbUlkIjogImNsYWltLTAwMSIsCiAgICAiYW1vdW50IjogMTUwLjAwLAogICAgIm9wZXJhdGlvbkRhdGUiOiAiMjAyNi0wOS0wNyIsCiAgICAiYmVuZWZpY2lhcnkiOiAiQW5hIEdvbWV6IgogIH0KfQ==",
    "messageId": "msg-999888777",
    "publishTime": "2026-09-07T00:00:01Z"
  }
}
```

Al decodificar `message.data` en Base64, se obtiene el evento sintético:

```json
{
  "contract_version": "CONTRACT_SYNTHETIC_V0",
  "eventId": "evt-001",
  "eventType": "SYNTHETIC_PAYMENT_REQUESTED",
  "operationId": "op-001",
  "traceId": "trace-001",
  "occurredAt": "2026-09-07T00:00:00Z",
  "operation": "INSERT",
  "entity": "synthetic_payment",
  "payload": {
    "claimId": "claim-001",
    "amount": 150.00,
    "operationDate": "2026-09-07",
    "beneficiary": "Ana Gomez"
  }
}
```

### Campos y Trazabilidad

| Campo | Tipo | Obligatorio | Descripción |
|---|---|---|---|
| `contract_version` | string | Sí | Fijo `CONTRACT_SYNTHETIC_V0`. |
| `eventId` | string | Sí | Proviene del evento decodificado (message.data). |
| `eventType` | string | Sí | Fijo `SYNTHETIC_PAYMENT_REQUESTED`. |
| `operationId` | string | Sí | **Clave de idempotencia.** Proviene del evento decodificado. |
| `traceId` | string | Sí | Correlación end-to-end. Proviene del evento decodificado. |
| `occurredAt` | string ISO8601 | Sí | Timestamp del evento. |
| `operation` | string | Sí | Fijo `INSERT`. Pre-filtrado por Worker antes de Vertex. |
| `entity` | string | Sí | Fijo `synthetic_payment`. Pre-filtrado por Worker. |
| `payload` | object | Sí | Ver §2. |

> **Nota aclaratoria:** `messageId` es metadato interno del envelope de Pub/Sub. `operationId`, `traceId` y `eventId` provienen del JSON decodificado.

---

## 2. Payload Sintético y Canonicalización de Hash

| Campo | Tipo | Obligatorio | Reglas |
|---|---|---|---|
| `claimId` | string | Sí | No vacío, max 64 chars. |
| `amount` | number | Sí | > 0, 2 decimales fijos (`150.00`). |
| `operationDate` | string | Sí | ISO date `YYYY-MM-DD`. |
| `beneficiary` | string | Sí | No vacío, max 100 chars. |

### 2.1 Algoritmo de Canonicalización de Hash (`PAYLOAD_HASH`)

Para evitar falsos rechazos en la idempotencia por diferencias de espacios o formateos JSON, el `PAYLOAD_HASH` se genera obligatoriamente así:
1. Claves del `payload` ordenadas alfabéticamente (`amount`, `beneficiary`, `claimId`, `operationDate`).
2. Sin espacios en blanco entre claves y valores.
3. Formateo de números monetarios a 2 decimales fijos (`150.00`).
4. Aplicación de SHA-256 en representación Hexadecimal (64 caracteres).

**Cadena canónica de ejemplo:**
`{"amount":"150.00","beneficiary":"Ana Gomez","claimId":"claim-001","operationDate":"2026-09-07"}`

**SHA-256 Hash Hex resultante:**
`8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6`

---

## 3. Flujo del Worker y Reserva Atómica (Prevención de Concurrencia)

### 3.1 Pre-filtro determinista del Worker
El Worker valida deterministamente contra `CATALOG_SYNTHETIC_V0.json` la terna `(entity, operation, eventType)` **antes** de llamar a Vertex AI. Si la entidad u operación no están en el catálogo $\rightarrow$ `REJECTED` directo en 2ms (ahorra costos y cuota).

### 3.2 Reserva Atómica vía `INSERT` directo (Sin Check-Then-Act)
Para evitar carreras de concurrencia en Cloud Run, la reserva se hace mediante un `INSERT` directo en `WORKER_OPERATION_STATE` con `STATUS = 'PROCESSING'`:

- **Si el INSERT es exitoso:** La instancia toma el control del procesamiento.
- **Si ocurre una colisión de Primary Key (PK Violation):** Significa que el `operationId` ya existe o está en vuelo.
  - Se consulta `WORKER_OPERATION_STATE`.
  - Si `STATUS == 'PROCESSING'` $\rightarrow$ Reintento en vuelo; esperar o retornar NACK para redelivery.
  - Si `STATUS == 'SUCCEEDED'` y `PAYLOAD_HASH` coincide $\rightarrow$ Retornar `IDEMPOTENT` (ACK).
  - Si `PAYLOAD_HASH` no coincide $\rightarrow$ Retornar `DLQ_QUARANTINED` (ACK tras evidenciar colisión).

---

## 4. Respuestas del Gobernador

### Respuesta APPROVED
```json
{
  "contract_version": "CONTRACT_SYNTHETIC_V0",
  "decision": "APPROVED",
  "operationId": "op-001",
  "traceId": "trace-001",
  "reason": "Operación validada correctamente",
  "target_table": "SYNTHETIC_PAYMENTS",
  "required_fields": ["claimId", "amount", "operationDate", "beneficiary"],
  "field_mapping": {
    "claimId": "COD_RECLAMO",
    "amount": "MONTO",
    "operationDate": "FECHA_OPER",
    "beneficiary": "BENEFICIARIO"
  },
  "value_rules": {},
  "catalog_version": "CATALOG_SYNTHETIC_V0"
}
```

### Respuesta REJECTED
```json
{
  "contract_version": "CONTRACT_SYNTHETIC_V0",
  "decision": "REJECTED",
  "operationId": "op-001",
  "traceId": "trace-001",
  "reason": "Operación no permitida o entidad no autorizada",
  "violations": ["operation_not_allowed"]
}
```

---

## 5. Salida Oficial del Traductor (8 Valores Posicionales)

El Worker combina el `operation_data` con las 4 columnas técnicas (`OPERATION_ID`, `TRACE_ID`, `EVENT_ID`, `PAYLOAD_HASH`). El Traductor produce el plan de 8 parámetros:

```json
{
  "status": "TRANSLATED",
  "sql_template": "INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
  "parameters": [
    "claim-001",
    150.00,
    "2026-09-07",
    "Ana Gomez",
    "op-001",
    "trace-001",
    "evt-001",
    "8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6"
  ],
  "preview_sql": "INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES ('claim-001', 150.00, '2026-09-07', 'Ana Gomez', 'op-001', 'trace-001', 'evt-001', '8c0f97a3...')"
}
```

---

## 6. Transacción Atómica en Sybase DEV e In-Doubt Recovery

```sql
BEGIN TRANSACTION;

-- 1. Insertar registro en SYNTHETIC_PAYMENTS incluyendo PAYLOAD_HASH
INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

-- 2. Actualizar estado en WORKER_OPERATION_STATE
UPDATE WORKER_OPERATION_STATE 
SET STATUS = 'SUCCEEDED', GOVERNOR_DECISION = 'APPROVED', TARGET_TABLE = 'SYNTHETIC_PAYMENTS', UPDATED_AT = GETDATE()
WHERE OPERATION_ID = ?;

COMMIT TRANSACTION;
```

> **Recuperación In-Doubt:** En caso de corte de red tras el `COMMIT` JDBC pero antes del ACK a Pub/Sub, ante la reentrega el Worker consulta `SYNTHETIC_PAYMENTS` por `OPERATION_ID`. Si la fila ya existe y su `PAYLOAD_HASH` coincide con el del evento recibido, promueve el estado a `SUCCEEDED` y responde ACK sin volver a intentar el INSERT.