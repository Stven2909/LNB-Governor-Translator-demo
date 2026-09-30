# Evidencia congelada — POC Pagaduría Digital (2026-09-22)

Snapshot **inmutable** de la entrega alineada al contrato oficial **`CONTRACT_PAYMENT_COMMITTED_V0.1`**
(vs. `CATALOG_PAYMENT_COMMITTED_V0.1`), generado por `DemoEvidenceTest` (`mvn test`) y por la
corrida en vivo (`scripts/demo-live-*.ps1`) contra el jar. La corrida V0 sintética (histórica)
sigue en `../2026-09-15/`.

| Archivo | Contenido |
|---|---|
| `evidencia_casos.json` | Documento machine-readable: meta, resumen 16/16 y detalle por escenario. |
| `EVIDENCIA_POC_LNB.md` | Matriz + detalle legible de los 16 escenarios E2E. |
| `demo-live-run.json` | Resultados HTTP de la demo en vivo (7 envelopes live-safe → **7/7 HTTP 200**). |
| `envelopes/*.json` | Envelopes reales usados por cada escenario (caso1.json … translation.json). |
| `envelopes/live/*.json` | Subconjunto que puede POSTearse contra un jar real (sin preparar estado). |

## Cómo se genera (reproducibilidad)

1. `mvn test` → regenera `target/demo/` completo con esta misma evidencia (37/37 suites).
2. `mvn -DskipTests package; scripts/demo-live-start.ps1; scripts/demo-live-run.ps1;
   scripts/demo-live-stop.ps1` → regenera `target/demo/demo-live-run.json` contra
   `http://localhost:8080/push` (7/7 HTTP 200).

## Limitaciones declaradas (para no malinterpretar la demo)

- Los 16 escenarios corren contra el pipeline real en memoria (Sybase, Gobernador, Traductor y
  endpoint de reporte son mocks deterministas).
- El escenario de evento en vuelo es simulado (registro PROCESSING pre-sembrado), no concurrencia
  real de hilos.
- La demo live POSTea solo 7 escenarios "safe" que no requieren estado pre-sembrado.
- El `demo-live-run.json` registra el response del primer POST de cada envelope; re-POSTear un
  mismo envelope al mismo jar cambiaría el estado (p. ej. caso1 → IDEMPOTENT).