# REPORTE EVIDENCIA — POC Prueba Vertical Pagaduría Digital (LNB)

**Fecha:** 15 septiembre 2026
**Estado:** pruebas automatizadas **20/20 verdes** · harness E2E **16/16 PASS** · demo en vivo **7/7 HTTP 200**

## 1. Qué se demostró

La prueba vertical completa del Worker de la Pagaduría Digital, ejecutando el pipeline
documentado en `CONTRACT_SYNTHETIC_V0` contra `CATALOG_SYNTHETIC_V0`:

```
Pub/Sub → decode Base64 → validación estructural → PAYLOAD_HASH canónico →
reserva atómica en WORKER_OPERATION_STATE (PK operationId) →
pre-filtro del catálogo (REJECTED sin invocar Vertex) →
Gobernador (Vertex AI, hoy mock) → validateGovernor (DLQ si alucina) →
Traductor (guard TRANSLATION_ERROR) → commit a SYNTHETIC_PAYMENTS →
ACK solo tras persistir evidencia
```

## 2. Resultados de la evidencia

- **`mvn test` → 20/20 verdes:** 13 pruebas del pipeline E2E + 3 de cobertura de ramas
  (Mockito puro) + 2 del hash canónico + 1 de contexto + **1 harness de 16 escenarios**.
- **Harness E2E (16 escenarios) → 16/16 PASS.** Cubre los 6 casos del documento de pruebas
  + subcasos y ramas: idempotencia (2a), evento en vuelo→NACK (2b), PK collision→DLQ (2c),
  pre-filtro→REJECTED (3), operación no permitida (3b), alucinación del Gobernador→DLQ (4),
  in-doubt ramas 1/2/3 (5), base64/JSON/campos malformados→DLQ (6×3), rechazo de negocio del
  Gobernador (paso 9), columna inventada por el Traductor→TRANSLATION_ERROR, y hash canónico Fijo.
- **Demo en vivo (HTTP real):** jar en `:8080`, `POST /push` con 7 envelopes reutilizables →
  **7/7 HTTP 200** con los estados esperados (`SUCCEEDED`, `IDEMPOTENT`, `REJECTED`, `DLQ_QUARANTINED`).

## 3. Dónde está la evidencia

| Artefacto | Ruta |
|---|---|
| Matriz + detalle por escenario (MD legible) | `worker-poc/docs/evidencia/2026-09-15/EVIDENCIA_POC_LNB.md` |
| Evidencia machine-readable | `worker-poc/docs/evidencia/2026-09-15/evidencia_casos.json` |
| Corrida en vivo (HTTP) | `worker-poc/docs/evidencia/2026-09-15/demo-live-run.json` |
| Envelopes por escenario / live-safe | `worker-poc/docs/evidencia/2026-09-15/envelopes/` y `.../envelopes/live/` |
| Runbook de reproducción | `worker-poc/DEMO.md` |

Todo se regenera con `mvn test` + `scripts/demo-live-*.ps1` (ver `DEMO.md`).

## 4. Decisiones/reglas verificadas de forma accionable

1. **Pre-filtro vs rechazo del Gobernador:** el Caso 3 es el Worker, sin invocar a Vertex
   (`verify(governor, never()).decide(...)`); el paso 9 es el Gobernador. Ambos terminan en
   `REJECTED` y **NUNCA en DLQ**. Se distingue por comportamiento, no solo por estado.
2. **Alucinación del Gobernador (Caso 4):** target_table fuera del catálogo → `DLQ_QUARANTINED`.
3. **Idempotencia segura:** misma `operationId` + mismo `PAYLOAD_HASH` → `IDEMPOTENT` ACK sin
   duplicar; mismo `operationId` + distinto hash → PK collision → `DLQ_QUARANTINED`.
4. **ACK/NACK correctos:** ACK (200) solo tras persistir evidencia (incluye cuarentenas);
   en-vuelo → NACK (500) para redelivery; retry reprocesa y llega a `SUCCEEDED`.
5. **Hash canónico bloqueado:** `8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6`
   (el publicado en el contrato `d340da60…` era incorrecto; ya corregido y testeado).

## 5. Limitaciones declaradas

- Sybase, Gobernador (Vertex) y Traductor son **mocks deterministas**; no hay JDBC/Pub-Sub real.
- El escenario "en vuelo" (2b) es **simulado** (registro PROCESSING pre-sembrado), no concurrencia real de hilos.
- Solo 7 de los 16 escenarios son POSTeables contra un jar real sin preparar estado (subcarpeta `envelopes/live/`).

## 6. Pendientes para cerrar la PoC

| Pendiente | Tipo | Fase |
|---|---|---|
| Checkpoint jConnect (driver Sybase → fixture `FIXTURE_SYNTHETIC_DEV.sql`) | local, hilo separado | 6 |
| Vertex AI real (Gobernador) y Pub/Sub → Cloud Run (`dulcet-listener-505916-n5`) | bloqueado por acceso | 8 |
| Decisiones formales de JD: stack Java 21 + Boot 4.1.x; transporte outbox vs Pub/Sub; alcance IA | decisión | 0–8 |