# Evidencia — Prueba Vertical Pagaduría Digital (LNB)

- **Proyecto:** Pagaduría Digital — prueba vertical PoC
- **Contrato:** CONTRACT_PAYMENT_COMMITTED_V0.1 · **Catálogo:** CATALOG_PAYMENT_COMMITTED_V0.1
- **Corrida:** 2026-09-30T14:17:46
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
| hasher | Contrato §2.1 | 0fd5240a…246a8 (contrato PAYMENT_COMMITTED V0.1) | 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8 | N/A (función pura, sin HTTP) | PASS |

## caso1 — Nuevo válido → SUCCEEDED
- **Caso/ref:** Caso 1 · pasos 1-14 del contrato
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-001","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** APPROVED · target=SYNTHETIC_PAYMENTS
- **Traductor:** INSERT INTO SYNTHETIC_PAYMENTS (ID_PAGO, COD_RECLAMO, ESTADO, MEDIO_PAGO, MONTO_BRUTO, MONTO_RETENCION, MONTO_NETO, MONEDA, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Resultado:** **PASS**

## caso2a — Réplica idéntica → IDEMPOTENT
- **Caso/ref:** Caso 2a
- **Esperado:** IDEMPOTENT · **Obtenido:** IDEMPOTENT · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-001","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** NO INVOCADO (respuesta inmediata por PAYLOAD_HASH idéntico)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** Primer POST → SUCCEEDED (mismo caso1 sobre estado limpio); este es el reenvío de Pub/Sub con la misma carga. El duplicado no re-ejecuta JDBC.
- **Resultado:** **PASS**

## caso2b — Evento en vuelo → RETRYABLE (NACK)
- **Caso/ref:** Caso 2b
- **Esperado:** RETRYABLE · **Obtenido:** RETRYABLE · **HTTP:** 500 (ack=false)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-flight","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
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
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-002","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-002","operationId":"op-001","operationData":{"payment_id":"pay-002","claim_id":"claim-002","status":"COMMITTED","payment_method":"CASH","gross_amount":"199.99","withholding_amount":"50.00","net_amount":"149.99","currency":"USD"}}`
- **PAYLOAD_HASH:** 48c8153e83b1d1fd565d15977e18aad464c8247b1b23e7890a6eca10299fc08b
- **Gobernador:** NO INVOCADO (colisión de PK detectada en la redelivery)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** operationId reciclado con datos distintos = PK collision → cuarentena, no redelivery.
- **Resultado:** **PASS**

## caso3 — AggregateType no autorizado → REJECTED (pre-filtro)
- **Caso/ref:** Caso 3 · pre-filtro del catálogo
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-003","event_type":"PAYMENT_COMMITTED","aggregate_type":"payroll_secret","aggregate_id":"agg-003","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-003","operationId":"op-rej-3","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** NO INVOCADO (pre-filtro del catálogo, ANTES de reservar; ver BranchingCoverageTest caso3)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** aggregateType fuera de la whitelist: el pre-filtro del Worker rechaza sin invocar a Vertex y sin reservar operationId.
- **Resultado:** **PASS**

## caso3b — eventType no permitido → REJECTED
- **Caso/ref:** Caso 3b
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-003b","event_type":"PAYMENT_CANCELLED","aggregate_type":"prizes.payment","aggregate_id":"agg-003b","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-003b","operationId":"op-rej-3b","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** NO INVOCADO (eventType fuera de la whitelist del catálogo)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** El catálogo solo acepta PAYMENT_COMMITTED para prizes.payment; cualquier otro eventType se rechaza por pre-filtro.
- **Resultado:** **PASS**

## caso4 — Gobernador alucina tabla → DLQ
- **Caso/ref:** Caso 4 · validateGovernor
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-hall","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** APPROVED · target=TABLA_INVENTADA (fuera de la whitelist)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=DLQ_QUARANTINED decision=- target=- attempt=1 error=target_table mismatch: expected SYNTHETIC_PAYMENTS got TABLA_INVENTADA
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** validateGovernor: la tabla devuelta por el LLM debe existir en el catálogo, si no → DLQ.
- **Resultado:** **PASS**

## caso5-r1 — in-doubt: pago ya aplicado → SUCCEEDED
- **Caso/ref:** Caso 5 · Rama 1
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-doubt","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** NO INVOCADO (in-doubt recuperado por PAYLOAD_HASH idéntico)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=- attempt=1 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** simulado: fila en SYNTHETIC_PAYMENTS + estado PROCESSING pre-sembrados; el Worker promueve a SUCCEEDED sin reescribir (commit ya aplicado).
- **Resultado:** **PASS**

## caso5-r2 — Retry (RETRYABLE) reprocesado → SUCCEEDED
- **Caso/ref:** Caso 5 · Rama 2
- **Esperado:** SUCCEEDED · **Obtenido:** SUCCEEDED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-retry","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** APPROVED · target=SYNTHETIC_PAYMENTS
- **Traductor:** INSERT INTO SYNTHETIC_PAYMENTS (ID_PAGO, COD_RECLAMO, ESTADO, MEDIO_PAGO, MONTO_BRUTO, MONTO_RETENCION, MONTO_NETO, MONEDA, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
- **Estado final (WORKER_OPERATION_STATE):** status=SUCCEEDED decision=APPROVED target=SYNTHETIC_PAYMENTS attempt=2 error=Operación validada correctamente
- **Pago (SYNTHETIC_PAYMENTS):** persistido
- **Cuarentena (DLQ):** []
- **Nota:** redelivery recibe RETRYABLE sin commit previo → reproceso completo → SUCCEEDED + pago.
- **Resultado:** **PASS**

## caso5-r3 — in-doubt con otro hash → DLQ
- **Caso/ref:** Caso 5 · Rama 3
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-bad","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** NO INVOCADO (inconsistencia de PAYLOAD_HASH detectada en StateStore)
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=DLQ_QUARANTINED decision=- target=- attempt=1 error=Inconsistent DB: no payment row or PAYLOAD_HASH mismatch for IN_DOUBT
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

## caso6-faltan — Faltan operationId y operationData → DLQ
- **Caso/ref:** Caso 6
- **Esperado:** DLQ_QUARANTINED · **Obtenido:** DLQ_QUARANTINED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-006","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-006","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-006","operationId":null,"operationData":null}`
- **PAYLOAD_HASH:** -
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** [key=- reason=Structural validation: operationId required,operationData required, key=- reason=Structural validation failed: operationId required, operationData required]
- **Nota:** Fallo de esquema ANTES de tocar la reserva atómica → DLQ estructural.
- **Resultado:** **PASS**

## paso9 — Gobernador rechaza por regla de negocio → REJECTED
- **Caso/ref:** Paso 9 del contrato
- **Esperado:** REJECTED · **Obtenido:** REJECTED · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-rej-9","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** REJECTED · reason=Regla de negocio: límite de monto excedido
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** status=REJECTED decision=- target=- attempt=1 error=Regla de negocio: límite de monto excedido
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** El rechazo del Gobernador (paso 9) es distinto del pre-filtro del Worker (Caso 3): aquí Vertex sí fue invocado, y el resultado REJECTED NO va a DLQ.
- **Resultado:** **PASS**

## translation — Traductor inventa columna → TRANSLATION_ERROR
- **Caso/ref:** Regla no_invented_columns
- **Esperado:** TRANSLATION_ERROR · **Obtenido:** TRANSLATION_ERROR · **HTTP:** 200 (ack=true)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-xtr","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** APPROVED · plan válido
- **Traductor:** sql_template con COLUMNA_INVENTADA (mock aislado; el DeterministicTranslator real no la produce)
- **Estado final (WORKER_OPERATION_STATE):** status=TRANSLATION_ERROR decision=- target=- attempt=1 error=Translator output violated whitelist: exactly 12 parameters required, got 13
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Nota:** Guard validateTranslation: tabla exacta, 12 parámetros y columnas ⊆ whitelist.
- **Resultado:** **PASS**

## hasher — PAYLOAD_HASH canónico del contrato
- **Caso/ref:** Contrato §2.1
- **Esperado:** 0fd5240a…246a8 (contrato PAYMENT_COMMITTED V0.1) · **Obtenido:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8 · **HTTP:** N/A (función pura, sin HTTP)
- **Evento decodificado:** `{"event_id":"evt-001","event_type":"PAYMENT_COMMITTED","aggregate_type":"prizes.payment","aggregate_id":"agg-001","event_version":1,"destination_system":"SYBASE","occurred_at":"2026-09-07T00:00:00Z","correlation_id":"corr-001","operationId":"op-hash","operationData":{"payment_id":"pay-001","claim_id":"claim-001","status":"COMMITTED","payment_method":"CASH","gross_amount":"200.00","withholding_amount":"50.00","net_amount":"150.00","currency":"USD"}}`
- **PAYLOAD_HASH:** 0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8
- **Gobernador:** -
- **Traductor:** -
- **Estado final (WORKER_OPERATION_STATE):** -
- **Pago (SYNTHETIC_PAYMENTS):** no persistido (no corresponde)
- **Cuarentena (DLQ):** []
- **Resultado:** **PASS**
