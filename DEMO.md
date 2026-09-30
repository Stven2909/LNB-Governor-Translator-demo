# DEMO — Prueba vertical Pagaduría Digital (LNB)

Demo presentable de la PoC: `mvn test` queda en **37/37** (24 E2E del pipeline + 4 ramas + 4
transiciones de estado + 3 hash + 1 smoke + 1 harness E2E de 16 escenarios) y una corrida en
vivo POSTea envelopes a `/push` devolviendo HTTP 200 con el estado esperado en cada caso.

## Reproducir la evidencia (harness E2E)

```powershell
mvn test
```

Esto regenera `target/demo/`:

- `evidencia_casos.json` — 16 escenarios, resumen 16/16 PASS.
- `EVIDENCIA_POC_LNB.md` — matriz + detalle legible.
- `envelopes/*.json` y `envelopes/live/*.json` — envelopes reutilizables.

## Demo en vivo (HTTP)

```powershell
mvn -DskipTests package
.\scripts\demo-live-start.ps1     # arranca el jar en :8080 y espera health UP
.\scripts\demo-live-run.ps1       # POSTea target\demo\envelopes\live\*.json a /push
.\scripts\demo-live-stop.ps1      # detiene el worker (por el listener de :8080)
```

Salida esperada (7/7 HTTP 200):

| Envelope | status |
|---|---|
| 01-caso1-SUCCEEDED.json | `SUCCEEDED` |
| 02-caso1-replica-IDEMPOTENT.json | `IDEMPOTENT` |
| 03-caso3-REJECTED.json | `REJECTED` (pre-filtro) |
| 04-caso3b-REJECTED.json | `REJECTED` (operación no permitida) |
| 05-caso6-b64-DLQ.json | `DLQ_QUARANTINED` |
| 06-caso6-json-DLQ.json | `DLQ_QUARANTINED` |
| 07-caso6-faltan-DLQ.json | `DLQ_QUARANTINED` |

El resultado de la corrida en vivo también se guarda en `target/demo/demo-live-run.json`.

## Snapshot congelado

La evidencia de esta entrega quedó copiada en `docs/evidencia/2026-09-22/` (lea su `README.md`).
La corrida V0 sintética (histórica) está en `docs/evidencia/2026-09-15/`.

## Cómo actúa cada código de estado

| Estado | Qué significa | Cómo se genera |
|---|---|---|
| `PROCESSING` | Reserva atómica hecha, pipeline en curso | — |
| `JDBC_COMMITTED` | INSERT confirmado a SYNTHETIC_PAYMENTS | `FixtureJdbcExecutor` → CONFIRMED |
| `REPORT_PENDING` | JDBC confirmado, enviando reporte | Tras JDBC_COMMITTED |
| `SUCCEEDED` | Pago persistido + reporte OK y ACK | Pipeline completo (caso 1); in-doubt conciliado por hash |
| `IDEMPOTENT` | Duplicado idéntico, ACK | PAYLOAD_HASH + estado ya SUCCEEDED |
| `RETRYABLE` | NACK para redelivery | Fallo JDBC temporal (sin fila), fallo de reporte (con fila) o evento en vuelo |
| `IN_DOUBT` | Commit JDBC indeterminado | `FixtureJdbcExecutor` → UNKNOWN; redelivery concilia por hash |
| `REJECTED` | No se procesa, no va a DLQ. ACK | Pre-filtro del catálogo, invariante monetaria o rechazo del Gobernador |
| `DLQ_QUARANTINED` | Basura/desviación; ACK y cuarentena | Base64/JSON malformados, PK collision, alucinación del Gobernador, in-doubt inconsistente |
| `TRANSLATION_ERROR` | El SQL inventa columnas | Guard del Traductor (solo con translator mock) |

Todas las transiciones las gobierna `OperationStateMachine` (p. ej. `SUCCEEDED → PROCESSING` o
`PROCESSING → SUCCEEDED` directos son inválidos).

## Evidencia por escenario

Los 16 escenarios del harness están en `src/test/java/com/pagaduriasintetica/worker/worker/DemoEvidenceTest.java`.
Los archivos `envelopes/*.json` bajo `target/demo/` son los que se POSTean; los de
`envelopes/live/` son los que sirven contra un jar real sin preparar estado.

## Limitaciones a declarar en la demo

- Sybase, Gobernador (Vertex) y Traductor son deterministas/mock; no hay JDBC ni Pub/Sub real.
- El escenario "en vuelo" es simulado (PROCESSING pre-sembrado), no concurrencia real de hilos.
- La verificación del hash de contrato (escenario 16) es una función pura, sin HTTP.