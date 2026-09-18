# PLAN_IMPLEMENTACION_V0.1 — Alineamiento del PoC al contrato oficial PAYMENT_COMMITTED

**Estado:** CONGELADO como plan de implementación (revisión final de Carlos incluida).
**Base contractual:** `CONTRACT_EVENTO_V0.1.md` (contrato de Alex) · `MINUTA_ALEX_RESULTADO_V1.md` · `AMBIENTE_DEV_LNB.md`.
**Arquitectura:** la acordada (Worker Gobernador-Traductor, endpoint de reporte Opción B, idempotencia por `operationId`).

---

## 1. Pipeline definitivo del PoC

```
flowchart TD
    A["Pub/Sub Push"] --> B["Validar sobre y evento"]
    B --> C["Pre-filtro del catálogo"]
    C --> D["Reserva atómica operationId"]
    D --> E{"Estado existente"}
    E -->|Nuevo| F["Gobernador"]
    E -->|JDBC confirmado| G["Reintentar reporte"]
    E -->|SUCCEEDED| H["ACK idempotente"]
    E -->|IN_DOUBT| I["Conciliación"]
    F --> J["Validar contrato y whitelist"]
    J --> K["Traductor determinista"]
    K --> L["JDBC / fixture"]
    L --> M["Persistir resultado"]
    M --> N["ResultReporter"]
    N --> O["SUCCEEDED y ACK"]
```

Orden canónico: **validar sobre Pub/Sub → pre-filtro estructural → reserva atómica por `operationId` → resolver redelivery según estado → Gobernador → validar GovernorContract/whitelist → Traductor → JDBC → reporte → ACK**.

Regla inviolable: **nunca invocar al Gobernador antes de reservar `operationId`**.

## 2. Orden de ejecución

1. Documentación
2. Contratos
3. Idempotencia / estados
4. Catálogo / Gobernador / Traductor
5. Reporte / pipeline
6. Pruebas
7. README
8. Commit y push

## 3. Fase 1 — Contratos

- `SyntheticEvent` → **`PaymentCommittedEvent`** (mismo package): `eventId, eventType(PAYMENT_COMMITTED), aggregateType(prizes.payment), aggregateId, eventVersion(=1), destinationSystem(SYBASE), occurredAt, correlationId, operationId, operationData`. Desaparecen `contract_version, operation, entity` (nivel evento).
- `SyntheticPayload` → **`OperationData`**: 8 campos oficiales (`paymentId, claimId, status, paymentMethod, grossAmount, withholdingAmount, netAmount, currency`; BigDecimal los 3 montos).
- **`OperationContext`** nuevo: `workerTraceId` (generado por el Worker) + `correlationId` (de Alex); ambos para logs/Vertex/`sync_attempt.trace_id`.
- `PushEnvelope`: + `deliveryAttempt` (transporte); se conservan `messageId/publishTime/data` (data base64 → `PAYMENT_COMMITTED`).
- `PayloadHasher`: canon SHA-256 sobre `OperationData` (montos a 2 decimales, `BigDecimal.setScale(2)`).
- `StructuralValidator`: 8 campos requeridos + `validationRules` (ver Fase 3).

## 4. Fase 2 — Idempotencia atómica y estados

### OperationStatus (nuevo)
`PROCESSING, JDBC_COMMITTED, REPORT_PENDING, SUCCEEDED, REJECTED, RETRYABLE, IN_DOUBT, IDEMPOTENT, TRANSLATION_ERROR, DLQ_QUARANTINED`.

### OperationStateMachine (nuevo)
Política de transiciones centralizada; toda transición pasa por ella; las inválidas se rechazan.

| De | Válidas a |
|---|---|
| PROCESSING | JDBC_COMMITTED · REJECTED · RETRYABLE · IN_DOUBT |
| JDBC_COMMITTED | REPORT_PENDING · (redelivery) |
| REPORT_PENDING | SUCCEEDED · RETRYABLE |
| SUCCEEDED | — (terminal) |

Inválidas, p. ej.: `SUCCEEDED → PROCESSING`.

### StateStore (operaciones)
Todas respetan `OperationStateMachine`:

- `reserveAtomic()`
- `markProcessing()`
- `markRejected()`
- `markJdbcCommitted()`
- `markRetryable()`
- `markInDoubt()`
- `markReportPending()`
- `markSucceeded()`

Recuperación en redelivery: `JDBC_COMMITTED → solo reintentar reporte` (nunca re-ejecutar JDBC); `IN_DOUBT → conciliación`; `SUCCEEDED + mismo hash → ACK IDEMPOTENT`; mismo `operationId` con distinto hash → `DLQ_QUARANTINED` (colisión de PK).

## 5. Fase 3 — Catálogo / Gobernador / Traductor

### Catálogo
- Clave por `aggregateType + eventType`; nuevo `CATALOG_PAYMENT_COMMITTED_V0.1.json`.
- **`value_rules` = SOLO transformaciones autorizadas** (de `OperationData` → plan de escritura fixture), nunca cómputo de negocio. Ejemplos:
  - `COMMITTED → <código legacy aprobado>`
  - `CASH → <código legacy aprobado>`
  - `USD → <código legacy aprobado>`
- **Invariante monetaria** en bloque aparte `validationRules`:
  ```json
  "validationRules": { "netAmount": "grossAmount - withholdingAmount" }
  ```
  Ejecutada **determinísticamente con `BigDecimal.compareTo`** (validación, `== 0`), sin depender del LLM y **sin recalcular silenciosamente** el valor.

### Gobernador (nombres explícitos)
`GovernorInput` (identificadores + `OperationData`) · `GovernorContract` (conserva `contract_version` y `catalog_version`; `eventVersion` NO sustituye a esos dos campos) · `MockGovernor` · prompt del Gobernador · `responseSchema` · **whitelist externa** (via `Catalog.validateGovernor`). El `governorContract` no contiene montos originales ni `source_sql` en el flujo real.

### Traductor
- `TranslatorInput(OperationData original + GovernorContract aprobado)`.
- `DeterministicTranslator`: 12 parámetros en fixture (8 de negocio + `OPERATION_ID`, `TRACE_ID` interno del Worker, `EVENT_ID`, `PAYLOAD_HASH`).
- `TECHNICAL_COLUMNS` y validación del template derivados del catálogo (dinámicos, no hardcode de 8).
- Fixture `SYNTHETIC_PAYMENTS`: placeholder documentado; **nunca apuntar a Sybase DEV real**.

## 6. Fase 4 — Reporte y pipeline

### JDBC
- Nombre para el fixture: **`executeFixtureTransaction()`** (INSERT + actualización de estado en la misma base sintética).
- Interfaz definitiva: **`JdbcExecutionResult execute(PreparedStatementSpec statement)`**.
- El Worker interpreta el resultado y NO hardcodea la transición:
  - `CONFIRMED → JDBC_COMMITTED`
  - `TEMPORARY_FAILURE → RETRYABLE`
  - `UNKNOWN → IN_DOUBT`
- La atomicidad local del fixture **NO demuestra atomicidad Sybase–almacenamiento del Worker**; dejar esto explícito en el código.

### ResultReporter
```
ResultReporter
├── MockResultReporter   # PoC actual
└── HttpResultReporter   # implementación desacoplada (posterior)
```
El mock se usa mientras Alex confirma **ruta, autenticación y códigos** del endpoint.

### WorkerService (orden del pipeline)
Pre-filtro estructural → reserva atómica → resolver redelivery por estado → Gobernador → validar contrato/whitelist → Traductor → `executeFixtureTransaction()` → persistir resultado (`JDBC_COMMITTED`) → `ResultReporter.report()` → `SUCCEEDED` y ACK.

## 7. Fase 5 — Pruebas

- Conservar las **20** existentes.
- **Agregar ~15**:
  1. `eventVersion` inválida.
  2. `aggregateType != prizes.payment`.
  3. `eventType != PAYMENT_COMMITTED`.
  4. `destinationSystem != SYBASE`.
  5. Campos obligatorios ausentes/malformados.
  6. Montos negativos.
  7. Inconsistencia `gross − withholding != net` (validationRules).
  8. Canonicalización decimal para SHA-256 (equivalencia `1.10 == 1.1`).
  9. Mismo `operationId`, distinto hash → colisión (DLQ).
  10. Duplicado en `JDBC_COMMITTED` → solo reintenta reporte.
  11. Transición inválida rechazada por `OperationStateMachine`.
  12. Resultado JDBC indeterminado → `IN_DOUBT` → conciliación.
  13. Fallo temporal del reporte → `RETRYABLE` hasta `SUCCEEDED`.
  14. 409 del endpoint → no re-ejecutar JDBC.
  15. Redelivery sin segunda ejecución JDBC.

## 8. Fase 6 — README

Actualizar arquitectura/pipeline/estados/contratos en `README.md`.

## 9. Dependencias externas

### No bloquean el PoC (fixtures y tests locales)
- `instrumentIds` (futuro arreglo de Alex).
- `operationId → idempotency_key` (afecta persistencia oficial, no el modelo lógico).
- Endpoint final del reporte (abstraído por `ResultReporter` + mock).

### Bloquean la prueba vertical real (ejecución contra Sybase DEV), NO los fixtures locales
- Mapping y tabla Sybase autorizados.
- Driver y credenciales JDBC.
- Conectividad Cloud Run → Sybase (Direct VPC / Serverless VPC Access / VPN).
- Mecanismo idempotente dentro de Sybase (pendiente LNB).
- Autenticación definitiva del endpoint (ruta, OIDC/IAM, códigos).
- Política real de retry/DLQ.

## 10. Criterio de terminado

Cada fase termina con `mvn test` en verde (las que afecten código); el PoC completo queda con 20 + ~15 pruebas pasando, README actualizado y, al final, commit + push.