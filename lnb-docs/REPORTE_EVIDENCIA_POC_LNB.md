# REPORTE EVIDENCIA — POC Prueba Vertical Pagaduría Digital (LNB)

**Fecha:** 22 septiembre 2026
**Estado:** pruebas automatizadas **37/37 verdes** · harness E2E **16/16 PASS** · demo en vivo **7/7 HTTP 200**
**Contrato:** `CONTRACT_PAYMENT_COMMITTED_V0.1` · **Catálogo:** `CATALOG_PAYMENT_COMMITTED_V0.1`

> Histórico: el snapshot de `docs/evidencia/2026-09-15/` documenta la corrida anterior (contrato
> sintético V0). Esta entrega corresponde al **paso de alineamiento PAYMENT_COMMITTED** del
> `PLAN_IMPLEMENTACION_V0.1.md` (fases 1–6, commit `3f725e8`).

## 1. Qué se demostró

La prueba vertical completa del Worker de la Pagaduría Digital ejecutando el pipeline del
contrato oficial confirmado por Alex (`PaymentCommittedEvent`/`OperationData`, 8 campos de
negocio) contra `CATALOG_PAYMENT_COMMITTED_V0.1`:

```
Pub/Sub → decode Base64 → validación estructural → PAYLOAD_HASH canónico →
reserva atómica en WORKER_OPERATION_STATE (PK operationId) →
pre-filtro del catálogo (REJECTED sin invocar Vertex, ANTES de reservar) →
invariante monetaria (netAmount == grossAmount − withholdingAmount, sin LLM) →
Gobernador (Vertex AI, hoy mock) → validateGovernor (DLQ si alucina) →
Traductor (INSERT de 12 parámetros: 8 negocio + OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) →
commit a SYNTHETIC_PAYMENTS (CONFIRMED/UNKNOWN → in-doubt) →
reporte al endpoint (SUCCEEDED/CONFLICT/FAILED) →
ACK solo tras persistir o clasificar evidencia
```

## 2. Resultados de la evidencia

- **`mvn test` → 37/37 verdes:**
  | Suite | Tests |
  |---|---|
  | `WorkerPipelineTest` (E2E con beans reales) | 24 |
  | `BranchingCoverageTest` (ramas, Mockito puro) | 4 |
  | `OperationStateMachineTest` (política de transiciones) | 4 |
  | `PayloadHasherTest` (hash canónico) | 3 |
  | `WorkerPocApplicationTests` (smoke de contexto) | 1 |
  | `DemoEvidenceTest` (harness E2E de 16 escenarios) | 1 |
- **Harness E2E (16 escenarios) → 16/16 PASS.** Cubre los 6 casos de la prueba vertical más
  subcasos y ramas: idempotencia (2a), evento en vuelo→NACK (2b), PK collision→DLQ sin degradar
  el SUCCEEDED (2c), pre-filtro→REJECTED (3), operación no permitida (3b), alucinación del
  Gobernador→DLQ (4), in-doubt r1/r2/r3 (5), base64/JSON/campos malformados→DLQ (6×3), rechazo
  de negocio del Gobernador (paso 9), columna inventada por el Traductor→TRANSLATION_ERROR y
  hash canónico fijo.
- **Demo en vivo (HTTP real):** jar en `:8080`, `POST /push` con 7 envelopes live-safe →
  **7/7 HTTP 200** con los estados esperados (`SUCCEEDED`, `IDEMPOTENT`, `REJECTED` ×2,
  `DLQ_QUARANTINED` ×3).

## 3. Dónde está la evidencia

| Artefacto | Ruta |
|---|---|
| Matriz + detalle por escenario (MD legible) | `worker-poc/docs/evidencia/2026-09-22/EVIDENCIA_POC_LNB.md` |
| Evidencia machine-readable | `worker-poc/docs/evidencia/2026-09-22/evidencia_casos.json` |
| Corrida en vivo (HTTP) | `worker-poc/docs/evidencia/2026-09-22/demo-live-run.json` |
| Envelopes por escenario / live-safe | `worker-poc/docs/evidencia/2026-09-22/envelopes/` y `.../envelopes/live/` |
| Runbook de reproducción | `worker-poc/DEMO.md` |

Todo se regenera con `mvn test` + `scripts/demo-live-*.ps1` (ver `DEMO.md`).

## 4. Decisiones/reglas verificadas de forma accionable

1. **Pre-filtro vs rechazo del Gobernador:** el Caso 3 es el Worker, sin invocar a Vertex
   (`verify(governor, never()).decide(...)`); el paso 9 es el Gobernador. Ambos terminan en
   `REJECTED` y **NUNCA en DLQ**.
2. **Alucinación del Gobernador (Caso 4):** target_table fuera del catálogo → `DLQ_QUARANTINED`.
3. **Idempotencia segura:** misma `operationId` + mismo `PAYLOAD_HASH` → `IDEMPOTENT` ACK sin
   duplicar y **sin re-ejecutar JDBC**; mismo `operationId` + distinto hash → colisión de PK →
   `DLQ_QUARANTINED` (un `SUCCEEDED` terminal no se degrada).
4. **ACK/NACK correctos:** ACK (200) solo tras persistir o clasificar evidencia; en vuelo → NACK
   (500) para redelivery; retry reprocesa y llega a `SUCCEEDED`; in-doubt se concilia por hash
   (r1 SUCCEEDED, r3 DLQ por hash distinto).
5. **Hash canónico bloqueado:** `0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8`
   (SHA-256 de `OperationData` canónico: pay-001 · claim-001 · COMMITTED · CASH · 200.00 · 50.00 ·
   150.00 · USD).
6. **Máquina de estados centralizada:** `OperationStateMachine` rechaza transiciones inválidas
   (`SUCCEEDED → PROCESSING`, `PROCESSING → SUCCEEDED`); la recuperación post-crash pasa por
   `IN_DOUBT → SUCCEEDED`.

## 5. Limitaciones declaradas

- Sybase, Gobernador (Vertex), Traductor y el endpoint de reporte son **mocks deterministas**;
  no hay JDBC/Pub-Sub/Vertex reales (`FixtureJdbcExecutor` y `MockResultReporter` son los puntos
  de sustitución).
- El escenario "en vuelo" (2b) es **simulado** (registro PROCESSING pre-sembrado), no concurrencia
  real de hilos.
- La atomicidad del fixture no prueba la transaccionalidad real Sybase/Estrato de almacenamiento
  (un UNKNOWN real exige decidir entre commit y no-commit).
- Solo 7 de los 16 escenarios son POSTeables contra un jar real sin preparar estado
  (subcarpeta `envelopes/live/`).

## 6. Pendientes para cerrar la PoC

| Pendiente | Tipo | Fase |
|---|---|---|
| Checkpoint jConnect (driver Sybase → fixture `FIXTURE_SYNTHETIC_DEV.sql`) | local, hilo separado | 6 |
| Vertex AI real (Gobernador), Traductor y Pub/Sub → Cloud Run (`dulcet-listener-505916-n5`) | bloqueado por acceso | 8 |
| Concurrencia real de redelivery (hilos) sobre la reserva atómica | PoC, post-cierre | 5 |
| Decisiones formales de JD: stack Java 21 + Boot 4.1.x; transporte outbox vs Pub/Sub; alcance IA | decisión | 0–8 |