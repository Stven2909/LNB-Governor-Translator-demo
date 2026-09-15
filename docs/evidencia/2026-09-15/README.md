# Evidencia congelada — POC Pagaduría Digital (2026-09-15)

Snapshot inmutable de la evidencia generada por `DemoEvidenceTest` (mvn test) y por
la corrida en vivo (`scripts/demo-live-*.ps1`) contra el jar.

| Archivo | Contenido |
|---|---|
| `evidencia_casos.json` | Documento machine-readable: meta, resumen 16/16 y detalle por escenario. |
| `EVIDENCIA_POC_LNB.md` | Matriz + detalle legible de los 16 escenarios E2E. |
| `demo-live-run.json` | Resultados HTTP de la demo en vivo (7 envelopes live-safe → 200). |
| `envelopes/*.json` | Los envelopes reales usados por cada escenario (caso1.json … translation.json). |
| `envelopes/live/*.json` | Subconjunto que puede POSTearse contra un jar real (sin preparar estado). |

## Cómo se genera (reproducibilidad)

1. `mvn test` → regenera `target/demo/` completo con esta misma evidencia.
2. `mvn -DskipTests package; scripts/demo-live-start.ps1; scripts/demo-live-run.ps1; scripts/demo-live-stop.ps1`
   → regenera `target/demo/demo-live-run.json` apuntando a `http://localhost:8080/push`.

## Limitaciones declaradas (para no malinterpretar la demo)

- Los 16 escenarios corren contra el pipeline real en memoria (Sybase, Gobernador y Traductor son mocks).
- El escenario de evento en vuelo es simulado (registro PROCESSING pre-sembrado), no concurrencia real de hilos.
- La demo live POSTea solo 7 escenarios "safe" que no requieren estado pre-sembrado.