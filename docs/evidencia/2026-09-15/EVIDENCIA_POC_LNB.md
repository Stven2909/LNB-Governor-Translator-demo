# Evidencia — Prueba Vertical Pagaduría Digital (LNB)

- **Proyecto:** Pagaduría Digital — prueba vertical PoC
- **Contrato:** CONTRACT_SYNTHETIC_V0 · **Catálogo:** CATALOG_SYNTHETIC_V0
- **Corrida:** 2026-09-15T12:03:49
- **Resultado:** 16/16 escenarios PASS

## Matriz de escenarios

| # | Caso/ref | Esperado | Obtenido | HTTP | Resultado |
|---|----------|----------|----------|------|-----------|
| caso1 | Caso 1 · pasos 1-14 del contrato | SUCCEEDED | SUCCEEDED | 200 (ack=true) | PASS |
| caso2a | Caso 2a | IDEMPOTENT | IDEMPOTENT | 200 (ack=true) | PASS |
| caso2b | Caso 2b | RETRYABLE | RETRYABLE | 500 (ack=false) | PASS |
| caso2c | Caso 2c | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| caso3 | Caso 3 · pre-filtro del catálogo | REJECTED | REJECTED | 200 (ack=true) | PASS |
| caso3b | Caso 3b | REJECTED | REJECTED | 200 (ack=true) | PASS |
| caso4 | Caso 4 · validateGovernor | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| caso5-r1 | Caso 5 · Rama 1 | SUCCEEDED | SUCCEEDED | 200 (ack=true) | PASS |
| caso5-r2 | Caso 5 · Rama 2 | SUCCEEDED | SUCCEEDED | 200 (ack=true) | PASS |
| caso5-r3 | Caso 5 · Rama 3 | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| caso6-b64 | Caso 6 | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| caso6-json | Caso 6 | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| caso6-faltan | Caso 6 | DLQ_QUARANTINED | DLQ_QUARANTINED | 200 (ack=true) | PASS |
| paso9 | Paso 9 del contrato | REJECTED | REJECTED | 200 (ack=true) | PASS |
| translation | Regla no_invented_columns | TRANSLATION_ERROR | TRANSLATION_ERROR | 200 (ack=true) | PASS |
| hasher | Contrato §2.1 | 8c0f97a3…389d6 (ver contrato §2.1) | 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6 | N/A (función pura, sin HTTP) | PASS |

## caso1 — Nuevo válido → SUCCEEDED
- **Caso/ref:** Caso 1 · pasos 1-14 del contrato
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-001","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** APPROVED · target=SYNTHETIC_PAYMENTS
- **Traductor:** INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Resultado:** **PASS**

## caso2a — Réplica idéntica → IDEMPOTENT
- **Caso/ref:** Caso 2a
- **Esperado:** IDEMPOTENT · **Obtenido:** IDEMPOTENT · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-001","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** NO INVOCADO (respuesta inmediata por PAYLOAD_HASH idéntico)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** Primer POST → SUCCEEDED (mismo caso1 sobre estado limpio); este es el reenvío de Pub/Sub con la misma carga.
- **Resultado:** **PASS**

## caso2b — Evento en vuelo → RETRYABLE (NACK)
- **Caso/ref:** Caso 2b
- **Esperado:** RETRYABLE · **Obtenido:** RETRYABLE · **HTTP:** 500 (ack=false)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-flight","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** NO INVOCADO (evento en vuelo, NACK para redelivery)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=PROCESSING decision=- target=- attempt=2
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** simulado — registro PROCESSING pre-sembrado en StateStore; no concurrencia real de hilos
- **Resultado:** **PASS**

## caso2c — Mismo operationId con otra carga → DLQ
- **Caso/ref:** Caso 2c
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-001","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-002","monto":"199.99","fecha_oper":"2026-09-08","beneficiario":"Pedro Ruiz"}}`
- **PAYLOAD_HASH:** b615c505a5e910784ec804ee47943d8e2fcc528c85b8ca0ae232d6af5c3a66b2
- **Gobernador:** NO INVOCADO (colisión de PK detectada en la reserva)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=DLQ_QUARANTINED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=operationId collision: different PAYLOAD_HASH
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** operationId reciclado con datos distintos = PK collision → cuarentena, no redelivery.
- **Resultado:** **PASS**

## caso3 — Entidad no autorizada → REJECTED (pre-filtro)
- **Caso/ref:** Caso 3 · pre-filtro del catálogo
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-003","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-rej-3","traceId":"trace-003","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payroll_secret","payload":{"cod_reclamo":"claim-003","monto":"5.00","fecha_oper":"2026-09-07","beneficiario":"Nomina Oculta"}}`
- **PAYLOAD_HASH:** eb8bfe43d66f8961dd7416fa77476c26f206a0cb8bfe66551bf0d9ef9dae24de
- **Gobernador:** NO INVOCADO (pre-filtro del catálogo; ver BranchingCoverageTest caso3)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=REJECTED decision=REJECTED target=- attempt=1 error=Operación no permitida o entidad no autorizada
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** El pre-filtro del Worker rechaza sin invocar a Vertex (Gobernador), a diferencia del paso 9.
- **Resultado:** **PASS**

## caso3b — Operación no permitida (DELETE) → REJECTED
- **Caso/ref:** Caso 3b
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-003b","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-del-3","traceId":"trace-003b","occurred_at":"2026-09-07T00:00:00Z","operation":"DELETE","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-003b","monto":"5.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 93fbb127c019aeabd77e7243a573bb8eb83c03de82825ea5425fdcdd5dda80a7
- **Gobernador:** NO INVOCADO (operación DELETE fuera de la whitelist del catálogo)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=REJECTED decision=REJECTED target=- attempt=1 error=Operación no permitida o entidad no autorizada
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Resultado:** **PASS**

## caso4 — Gobernador alucina tabla → DLQ
- **Caso/ref:** Caso 4 · validateGovernor
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-hall","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** APPROVED · target=TABLA_INVENTADA (fuera de la whitelist)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=DLQ_QUARANTINED decision=APPROVED target=TABLA_INVENTADA attempt=1 error=Governor output violated catalog whitelist: target_table mismatch: expected SYNTHETIC_PAYMENTS got TABLA_INVENTADA
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** validateGovernor: la tabla devuelta por el LLM debe existir en el catálogo, si no → DLQ.
- **Resultado:** **PASS**

## caso5-r1 — in-doubt: pago ya aplicado → SUCCEEDED
- **Caso/ref:** Caso 5 · Rama 1
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-doubt","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** NO INVOCADO (in-doubt recuperado por PAYLOAD_HASH idéntico)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Promoted after in-doubt recovery (commit already applied)
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** simulado: fila en SYNTHETIC_PAYMENTS + estado PROCESSING pre-sembrados; el Worker promueve a SUCCEEDED sin reescribir (commit ya aplicado).
- **Resultado:** **PASS**

## caso5-r2 — Retry (RETRYABLE) reprocesado → SUCCEEDED
- **Caso/ref:** Caso 5 · Rama 2
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-retry","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** APPROVED · target=SYNTHETIC_PAYMENTS
- **Traductor:** INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=2 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** redelivery recibe PROCESSING; dueño pendiente → reproceso con estado PROMOVER a SUCCEEDED.
- **Resultado:** **PASS**

## caso5-r3 — in-doubt con otro hash → DLQ
- **Caso/ref:** Caso 5 · Rama 3
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-bad","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** NO INVOCADO (inconsistencia de PAYLOAD_HASH detectada en StateStore)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=DLQ_QUARANTINED decision=- target=- attempt=1 error=Inconsistent DB: payment row exists with different PAYLOAD_HASH
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** fila de pago existente con PAYLOAD_HASH distinto = inconsistencia de BD → cuarentena.
- **Resultado:** **PASS**

## caso6-b64 — Base64 corrupto → DLQ
- **Caso/ref:** Caso 6
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **PAYLOAD_HASH:** -
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** [key=- reason=Invalid Base64 envelope data]
- **Resultado:** **PASS**

## caso6-json — JSON del evento malformado → DLQ
- **Caso/ref:** Caso 6
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **PAYLOAD_HASH:** -
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** [key=- reason=Malformed JSON payload, key=- reason=Malformed JSON payload]
- **Resultado:** **PASS**

## caso6-faltan — Faltan operationId y payload → DLQ
- **Caso/ref:** Caso 6
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-006","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":null,"traceId":null,"occurred_at":null,"operation":"INSERT","entity":"synthetic_payment","payload":null}`
- **PAYLOAD_HASH:** -
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** [key=- reason=Structural validation: operationId required,traceId required,occurredAt must be ISO8601,payload required, key=- reason=Structural validation failed: operationId required, traceId required, occurredAt must be ISO8601, payload required]
- **Resultado:** **PASS**

## paso9 — Gobernador rechaza por regla de negocio → REJECTED
- **Caso/ref:** Paso 9 del contrato
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-rej-9","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** REJECTED · reason=Regla de negocio: límite de monto excedido
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=REJECTED decision=REJECTED target=- attempt=1 error=Regla de negocio: límite de monto excedido
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** El rechazo del Gobernador (paso 9) es distinto del pre-filtro del Worker (Caso 3): aquí Vertex sí fue invocado, y el resultado REJECTED NO va a DLQ.
- **Resultado:** **PASS**

## translation — Traductor inventa columna → TRANSLATION_ERROR
- **Caso/ref:** Regla no_invented_columns
- **Esperado:** TRANSLATION_ERROR · **Obtenido:** TRANSLATION_ERROR · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-xtr","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** APPROVED · plan válido
- **Traductor:** sql_template con COLUMNA_INVENTADA (mock aislado; el DeterministicTranslator real no la produce)
- **Estado final (WORKER_OPERATION_STATE):** status=TRANSLATION_ERROR decision=- target=- attempt=1 error=Translator output violated whitelist: exactly 8 parameters required, got 9
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** Guard validateTranslation: tabla exacta, 8 parámetros y columnas ⊆ whitelist. Ver BranchingCoverageTest caso_translatorDevuelveColumnaInventadaEsTranslationError.
- **Resultado:** **PASS**

## hasher — PAYLOAD_HASH canónico del contrato
- **Caso/ref:** Contrato §2.1
- **Esperado:** 8c0f97a3…389d6 (ver contrato §2.1) · **Obtenido:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6 · **HTTP:** N/A (función pura, sin HTTP)
- **Evento decodificado:** `{"contract_version":"CONTRACT_SYNTHETIC_V0","event_id":"evt-001","event_type":"SYNTHETIC_PAYMENT_REQUESTED","operationId":"op-hash","traceId":"trace-001","occurred_at":"2026-09-07T00:00:00Z","operation":"INSERT","entity":"synthetic_payment","payload":{"cod_reclamo":"claim-001","monto":"150.00","fecha_oper":"2026-09-07","beneficiario":"Ana Gomez"}}`
- **PAYLOAD_HASH:** 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Resultado:** **PASS**
