# CONTRACT_EVENTO_V0.1

**Estado:** Borrador alineado al contrato oficial confirmado por Alex (17 sep 2026) — pendiente de cierre final (ver §8)
**Versión:** CONTRACT_EVENTO_V0.1 (evoluciona a V1 tras confirmaciones de Alex)
**Fecha:** 17 septiembre 2026
**Responsables:** Carlos, Henry/Steven (equipo de agentes)
**Base:** Respuesta de Alex (17 sep 2026) a la minuta de preguntas + `LNB_ER_Tecnico_PostgreSQL_DEV_102_v2.html` (ER 102 v2)
**Relación con V0:** `CONTRACT_SYNTHETIC_V0.md` queda como prueba técnica de plataforma (fixture). Este documento define el **contrato oficial del evento** que reemplazará el vocabulario sintético en el código (paso de alineamiento pendiente).

---

## 1. Operación prioritaria
- **Operación de negocio:** commit de pago — `POST /payments/{paymentId}:commit` (Bloque A, paso A6).
- El mismo contrato de evento se extiende a las demás operaciones (p. ej. creación de reclamo).

## 2. Evento publicado (JSON completo en `integration.outbox_event.payload_json`)

```json
{
  "eventId": "3f9a2b1e-7c44-4e9a-9b2a-1a2b3c4d5e6f",
  "eventType": "PAYMENT_COMMITTED",
  "aggregateType": "prizes.payment",
  "aggregateId": "pay-991",
  "eventVersion": 1,
  "destinationSystem": "SYBASE",
  "occurredAt": "2026-09-06T15:04:22Z",
  "correlationId": "TRACE-991",
  "operationId": "127f5d3a-...",
  "operationData": {
    "paymentId": "pay-991",
    "claimId": "1f9c...",
    "status": "COMMITTED",
    "paymentMethod": "CASH",
    "grossAmount": "3000.00",
    "withholdingAmount": "300.00",
    "netAmount": "2700.00",
    "currency": "USD"
  }
}
```

**Convenciones (confirmadas por Alex):**
- **camelCase en todo el JSON** (sobre y `operationData`) — misma convención que la API REST.
- `eventType` en `UPPER_SNAKE_CASE`, en participio (`PAYMENT_COMMITTED`), misma convención de `x-audit-event`.
- `aggregateType` = nombre canónico `esquema.tabla` de la BD real (**esquema en plural**: `prizes.payment`, `prizes.prize_claim`).
- `aggregateId` = UUID del agregado.
- Identificadores: `Idempotency-Key → operationId` · `X-Correlation-Id → correlationId` · `eventId = outbox_event_id` · `messageId` = interno de Pub/Sub (no parte del contrato).

## 3. Campos del sobre (envelope)

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `eventId` | string (uuid) | Sí | `eventId = outbox_event_id` exacto |
| `eventType` | string | Sí | `PAYMENT_COMMITTED` |
| `aggregateType` | string | Sí | `prizes.payment` (esquema.tabla, plural) |
| `aggregateId` | string | Sí | UUID del agregado (`pay-991`) |
| `eventVersion` | int | Sí | Nombre oficial (no `schemaVersion`/`contractVersion`); columna `event_version`, inicia en `1` |
| `destinationSystem` | string | Sí | `SYBASE` (columna `destination_system_code`) |
| `occurredAt` | string ISO8601 | Sí | Timestamp del evento |
| `correlationId` | string | Sí | Correlación end-to-end (**no `traceId`**; el `traceId` solo vive en bitácora de negocio y `sync_attempt`) |
| `operationId` | string | Sí | Clave de idempotencia (`Idempotency-Key`); ver discrepancia en §7.1 |
| `operationData` | object | Sí | Ver §4 |

## 4. operationData — commit de pago

| Campo | Tipo | Notas |
|---|---|---|
| `paymentId` | string | UUID del pago (`pay-991`) |
| `claimId` | string | UUID del reclamo; **posible futuro: arreglo `instrumentIds`** (varios boletos por reclamo, tabla puente `prizes.payment_item`) |
| `status` | string | `COMMITTED` (catálogo `catalogs.payment_status.code`) |
| `paymentMethod` | string | `CASH` (catálogo `catalogs.payment_method.code`) |
| `grossAmount` | decimal | `numeric(18,2)` |
| `withholdingAmount` | decimal | `numeric(18,2)` |
| `netAmount` | decimal | `numeric(18,2)` — invariante: `netAmount == grossAmount - withholdingAmount` |
| `currency` | string | `char(3)` (ISO 4217, ej. `USD`) |

## 5. Correspondencia del evento con `integration.outbox_event` (ER 102 v2, verificado)

| Campo evento | Columna `outbox_event` |
|---|---|
| `eventId` | `outbox_event_id` (PK) |
| `eventType` | `event_type` |
| `aggregateType` | `aggregate_type` |
| `aggregateId` | `aggregate_id` |
| `eventVersion` | `event_version` (`CHECK event_version > 0`) |
| `destinationSystem` | `destination_system_code` (FK → `integration.source_system`) |
| `occurredAt` | `occurred_at` |
| `correlationId` | `correlation_id` (tiene índice) |
| `operationId` | `idempotency_key` (asumido — **a confirmar**, ver §7.1) |
| (documento completo) | `payload_json` (jsonb) |

Otras columnas del ciclo: `event_status`, `available_at`, `published_at`, `destination_confirmed_at`, `retry_count` (`>= 0`), `created_at`.
Constraint de idempotencia: **`uq_outbox_idempotency UNIQUE(destination_system_code, idempotency_key)`**.

## 6. Volumen transaccional
Confirmado por Alex: operación de negocio + historial + auditoría + **idempotencia** + registro `integration.outbox_event` → todo en **la misma transacción PostgreSQL**; la **publicación ocurre después del COMMIT**, de forma asíncrona, por un publicador que lee eventos pendientes.
→ Consistente con la regla del PoC «ACK solo tras persistir evidencia».

## 7. Resultado de sincronización (mecanismo del MVP)
- **Acordado (enviado a Alex):** endpoint interno de reporte administrado por la API → la API registra `integration.sync_attempt` y actualiza `integration.outbox_event`/`destination_confirmed_at` en una transacción PG. Sin escritura directa del Worker en Cloud SQL. Ver `MINUTA_ALEX_RESULTADO_V1.md`.
- **Idempotencia del reporte:** `eventId + attemptNumber` ↔ **`uq_sync_attempt UNIQUE(outbox_event_id, attempt_number)`** (verificado en ER 102 v2).
- **Tres niveles de idempotencia:**
  1. Procesamiento del Worker → `operationId`.
  2. Reporte a la API → `eventId + attemptNumber`.
  3. Ejecución en Sybase → **pendiente de LNB** (clave única / tabla de control / procedimiento autorizado). No asumir que existe; mientras no exista, COMMIT incierto = `IN_DOUBT` → conciliación manual, sin reejecutar JDBC.
- **Vocabulario de procesos del Worker:** `RECEIVED → PROCESSING → JDBC_COMMITTED → REPORT_PENDING → SUCCEEDED` (+ `RETRYABLE`, `REJECTED`, `IN_DOUBT`, `DLQ_QUARANTINED`, `TRANSLATION_ERROR`).

## 8. Pendientes de confirmación (Alex y/o LNB)
1. **`operationId` → `outbox_event.idempotency_key`** (el ER 102 v2 no tiene columna `operation_id`) o columna propia — pendiente de Alex.
2. **`claimId` → arreglo `instrumentIds`** (varios instrumentos por reclamo) — pendiente de Alex.
3. Códigos finales de `outcome` del endpoint (`SUCCEEDED`, `RETRYABLE`, `REJECTED`, `IN_DOUBT` a validar).
4. Ruta + autenticación del endpoint de reporte (Cloud Run directo vs API Gateway; OIDC/IAM).
5. Idempotencia en **Sybase** (pendiente de LNB, externo a Alex).
6. Vocabulario definitivo de estados de procesamiento.

## 9. Cambios pendientes de aplicar en el PoC (paso de alineamiento)
1. `SyntheticEvent`: `contract_version` → `eventVersion`; `traceId` → `correlationId`; `entity/operation` → `aggregateType`/`aggregateId`; `eventType` → `PAYMENT_COMMITTED`; eliminar `operation`/`entity` del evento.
2. `SyntheticPayload` → `OperationData` con los 8 campos oficiales.
3. `StructuralValidator`: validar los 8 campos + invariante `net == gross - withholding`.
4. `PayloadHasher`: canonicalización sobre `operationData` (mismo algoritmo, nuevos campos).
5. `Catalog`: clave por `prizes.payment` + `PAYMENT_COMMITTED`; `field_mapping` y columnas actualizados.
6. `DeterministicTranslator`: SQL dinámico a 12 parámetros (8 negocio + 4 técnicas).
7. Fixture SQL y los 20 tests reescritos al nuevo shape.
8. README/diagrama y evidencia al vocabulario oficial.

## 10. Referencias
- Respuesta de Alex (17 sep 2026) a la minuta de preguntas.
- `LNB_ER_Tecnico_PostgreSQL_DEV_102_v2.html` (ER 102 v2 — 102 tablas/16 schemas).
- `MINUTA_ALEX_RESULTADO_V1.md` (mecanismo de resultado, enviada el 17 sep 2026).
- `AMBIENTE_DEV_LNB.md` (accesos/recursos DEV).
- `CONTRACT_SYNTHETIC_V0.md` + `CATALOG_SYNTHETIC_V0.json` (baseline técnico V0).