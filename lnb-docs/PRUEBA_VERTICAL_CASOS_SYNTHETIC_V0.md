# Prueba Vertical — 6 Casos con CONTRACT_SYNTHETIC_V0

**Contrato:** `CONTRACT_SYNTHETIC_V0` + `CATALOG_SYNTHETIC_V0` + `FIXTURE_SYNTHETIC_DEV.sql`
**Infra:** Pub/Sub (push) → Cloud Run (Worker) → Gobernador → Catálogo Determinista → Traductor → JDBC → Sybase DEV
**Criterio de salida (Propuesta v2 §5):** Evento sintético decodificado llega a `SYNTHETIC_PAYMENTS`, estado de idempotencia persiste de forma atómica en `WORKER_OPERATION_STATE`, logs reconstruyen el flujo y `operationId` repetido no duplica inserciones.

---

## Caso 1 — Nuevo válido → SUCCEEDED

1. **Entrada:** Envelope Push Pub/Sub con `message.data` Base64 del evento sintético válido.
2. **Procesamiento:**
   - Worker decodifica Base64, valida IDs (`operationId: op-001`, `eventId`, `traceId`) y genera `PAYLOAD_HASH` canónico (SHA-256 hex).
   - Worker realiza reserva atómica mediante `INSERT` directo en `WORKER_OPERATION_STATE` (Sybase DEV) con `STATUS = 'PROCESSING'`.
   - Worker pre-filtra `(entity, operation, eventType)` contra `CATALOG_SYNTHETIC_V0.json` $\rightarrow$ OK.
   - Invoca Gobernador $\rightarrow$ Devuelve `APPROVED`.
   - Worker post-valida respuesta del Gobernador contra `CATALOG_SYNTHETIC_V0.json` $\rightarrow$ OK.
   - Traductor genera plan parametrizado de 8 valores.
   - JDBC ejecuta transacción atómica en Sybase DEV: `INSERT INTO SYNTHETIC_PAYMENTS` + `UPDATE WORKER_OPERATION_STATE SET STATUS = 'SUCCEEDED'`.
   - Worker responde `HTTP 200 (ACK)` a Pub/Sub.
3. **Evidencia:** Registro en `SYNTHETIC_PAYMENTS` con `OPERATION_ID=op-001`, `PAYLOAD_HASH` y estado `SUCCEEDED` en `WORKER_OPERATION_STATE`.

---

## Caso 2 — Duplicado (Mismo operationId) → IDEMPOTENT / DLQ_QUARANTINED

1. **Entrada:** Reenviar Caso 1.
2. **Procesamiento:**
   - Worker intenta reserva atómica mediante `INSERT` directo en `WORKER_OPERATION_STATE`.
   - La base de datos arroja **Primary Key Violation** (`PK_WORKER_OPERATION_STATE`).
   - Worker atrapa la excepción y consulta `WORKER_OPERATION_STATE` por `operationId: op-001`:
     - **Subcaso A (Mismo PAYLOAD_HASH y STATUS == 'SUCCEEDED'):** Mismo evento reenviado por Pub/Sub $\rightarrow$ Registra `IDEMPOTENT`, responde `HTTP 200 (ACK)` inmediatamente sin invocar Gobernador, Traductor ni JDBC.
     - **Subcaso B (Mismo PAYLOAD_HASH y STATUS == 'PROCESSING'):** Evento idéntico en vuelo concurrente $\rightarrow$ Responde `HTTP 500 (NACK)` para reentrega programada por Pub/Sub.
     - **Subcaso C (Distinto PAYLOAD_HASH):** `operationId` reciclado con datos distintos $\rightarrow$ Registra `DLQ_QUARANTINED` por colisión de IDs, guarda evidencia y envía `HTTP 200 (ACK)`.

---

## Caso 3 — Rechazo por regla de negocio → REJECTED (Pre-filtro Worker)

1. **Entrada:** Evento sintético con `operation: "DELETE"` o `entity: "payroll_secret"`.
2. **Procesamiento:**
   - Worker pre-valida envelope $\rightarrow$ OK.
   - Worker efectúa reserva atómica `PROCESSING` en `WORKER_OPERATION_STATE`.
   - Worker evalúa pre-filtro determinista contra `CATALOG_SYNTHETIC_V0.json` $\rightarrow$ Detecta que la operación no está en la whitelist.
   - Worker marca `STATUS = 'REJECTED'`, `GOVERNOR_DECISION = 'REJECTED'` directamente sin llamar a Vertex AI (ahorra cuota/costos).
   - No se invoca Traductor ni JDBC. Responde `HTTP 200 (ACK)` a Pub/Sub.

---

## Caso 4 — Violación del catálogo por alucinación → DLQ_QUARANTINED

1. **Entrada:** Mockear respuesta del Gobernador respondiendo `APPROVED` pero con `target_table: "TABLA_INVENTADA"` o columnas no autorizadas.
2. **Procesamiento:**
   - Worker ejecuta post-validación determinista contra `CATALOG_SYNTHETIC_V0.json`.
   - Detecta discrepancia de tabla/columnas $\rightarrow$ Corta el flujo inmediatamente antes del Traductor/JDBC.
   - Registra `STATUS = 'DLQ_QUARANTINED'`, `ERROR_REASON = 'Governor output violated catalog whitelist'`.
   - Envía `HTTP 200 (ACK)` tras guardar evidencia de auditoría.

---

## Caso 5 — Falla temporal (Vertex 429 / Timeout JDBC) → RETRYABLE / In-Doubt Recovery

1. **Entrada:** Simular falla de red con Vertex AI o timeout JDBC durante la inserción.
2. **Procesamiento:**
   - Worker detecta error recuperable $\rightarrow$ Registra `STATUS = 'RETRYABLE'`, responde `HTTP 500 / 429 (NACK)` a Pub/Sub para solicitar reentrega programada.
   - **Recuperación In-Doubt (Commit JDBC aplicado pero ACK de Pub/Sub perdido):**
     Ante la reentrega, si `WORKER_OPERATION_STATE` quedó en `PROCESSING`/`RETRYABLE`, el Worker consulta `SYNTHETIC_PAYMENTS` por `OPERATION_ID`:
     - **Rama 1 (Existe en `SYNTHETIC_PAYMENTS` y `PAYLOAD_HASH` coincide):** El commit JDBC ocurrió exitosamente. El Worker promueve `WORKER_OPERATION_STATE` a `SUCCEEDED` y responde `HTTP 200 (ACK)` sin re-ejecutar la inserción.
     - **Rama 2 (No existe en `SYNTHETIC_PAYMENTS`):** El commit no ocurrió. El Worker reintenta el procesamiento completo.
     - **Rama 3 (Existe pero `PAYLOAD_HASH` no coincide):** Inconsistencia grave de BD $\rightarrow$ Registra `DLQ_QUARANTINED` y responde ACK.

---

## Caso 6 — Mensaje inválido / Base64 corrupto → DLQ_QUARANTINED

1. **Entrada:** Envelope con JSON mal formado, Base64 ilegible o faltante de `operationId`/`payload`.
2. **Procesamiento:**
   - Pre-validación del Worker detecta falla de integridad estructural.
   - Registra `STATUS = 'DLQ_QUARANTINED'`, `ERROR_REASON = 'Malformed JSON / Invalid Base64 envelope'`.
   - Responde `HTTP 200 (ACK)` tras guardar evidencia para evitar loops infinitos de reentrega en Pub/Sub.

---

## Orden de Ejecución del Worker (14 Pasos Definitivos)

1. Recibir HTTP Push de Pub/Sub.
2. Decodificar `message.data` en Base64. Si falla $\rightarrow$ `DLQ_QUARANTINED` + ACK.
3. Pre-validar integridad estructural del JSON y presencia de `operationId`, `eventId`, `traceId`.
4. Calcular `PAYLOAD_HASH` canónico (SHA-256 hex con claves ordenadas alfabéticamente y números `%.2f`).
5. Intentar reserva atómica mediante `INSERT` en `WORKER_OPERATION_STATE` con `STATUS = 'PROCESSING'`.
6. Si ocurre Primary Key Violation:
   - Si `PAYLOAD_HASH` coincide y `STATUS == 'SUCCEEDED'` $\rightarrow$ Retornar `IDEMPOTENT` + ACK.
   - Si `PAYLOAD_HASH` coincide y `STATUS == 'PROCESSING'` $\rightarrow$ Retornar NACK (esperar procesamiento en vuelo).
   - Si `PAYLOAD_HASH` difiere $\rightarrow$ Registrar `DLQ_QUARANTINED` + ACK.
7. Pre-filtrar deterministamente `(entity, operation, eventType)` contra `CATALOG_SYNTHETIC_V0.json`. Si no está en whitelist $\rightarrow$ Actualizar estado a `REJECTED` + ACK (sin Vertex).
8. Invocar al Gobernador con el evento normalizado.
9. Si Gobernador responde `REJECTED` $\rightarrow$ Actualizar estado a `REJECTED` + ACK (sin Traductor/JDBC).
10. Si Gobernador responde `APPROVED`, post-validar contrato contra `CATALOG_SYNTHETIC_V0.json`. Si falla $\rightarrow$ `DLQ_QUARANTINED` + ACK.
11. Invocar al Traductor para obtener `sql_template` y `parameters` (8 valores).
12. Iniciar transacción JDBC local en Sybase DEV.
13. Ejecutar `INSERT INTO SYNTHETIC_PAYMENTS` + `UPDATE WORKER_OPERATION_STATE SET STATUS = 'SUCCEEDED'`.
14. Hacer `COMMIT` JDBC y responder `HTTP 200 (ACK)` a Pub/Sub.