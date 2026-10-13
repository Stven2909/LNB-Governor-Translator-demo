# Evidencia de validación local

## Repositorio

- Repositorio: https://github.com/Stven2909/LNB-Governor-Translator-demo
- Rama: master
- Commit de referencia: 794394c6ef57ed1f77642fed6d836d4515cefa4c
- Enlace al commit: https://github.com/Stven2909/LNB-Governor-Translator-demo/commit/794394c6ef57ed1f77642fed6d836d4515cefa4c

## Ejecución Maven

- Comando ejecutado: `.\mvnw.cmd -B verify` (con
  `$env:JAVA_HOME="C:\Program Files\Java\jdk-21.0.12"`, porque el `JAVA_HOME`
  global apunta a una ruta inexistente; el JDK 21.0.12 sí existe)
- Fecha y hora: 2026-10-10T17:43:42-06:00
- Resultado: `BUILD SUCCESS`
- Pruebas ejecutadas: 75
- Fallos: 0
- Errores: 0
- Omitidas: 0
- Clases de prueba: 10
- Artefacto generado: JAR construido en la misma ejecución
  (`target/worker-poc-0.0.1-SNAPSHOT.jar`, reempaquetado por Spring Boot)

> Método: el árbol de trabajo contiene la clase sin seguimiento
> `src/test/java/com/pagaduriasintetica/worker/translator/DeterministicTranslatorTest.java`
> (3 pruebas, preexistente). Para que la ejecución refleje exactamente el commit de
> referencia, esa carpeta se apartó temporalmente fuera del repo durante la corrida y se
> restauró intacta después. No se modificó código de producción ni tests existentes;
> el archivo sigue en disco sin seguimiento (no forma parte de esta evidencia).

Desglose por clase (esta ejecución):

| Clase | Pruebas |
|---|---|
| `worker.worker.WorkerPipelineTest` | 24 |
| `worker.worker.CommitMeansSuccessTest` | 12 |
| `worker.config.WorkerPropertiesTest` | 11 |
| `worker.worker.GovernorFailureClassificationTest` | 8 |
| `worker.worker.ConcurrentRedeliveryTest` | 7 |
| `worker.worker.BranchingCoverageTest` | 4 |
| `worker.worker.OperationStateMachineTest` | 4 |
| `worker.worker.PayloadHasherTest` | 3 |
| `worker.worker.DemoEvidenceTest` | 1 |
| `worker.WorkerPocApplicationTests` | 1 |

Detalle método por método: `docs/REPORTE_JD_2026-10-06.md` §2.1–§2.10.
Mecanismo: Maven (`mvnw`/`mvnw.cmd`); no hay Gradle ni otro runner
(`pom.xml`, Spring Boot 4.1.1, Java 21).

## Harness de escenarios

- Resultado: 16/16 escenarios PASS.
- Los 16 escenarios están cubiertos por el harness local
  (`worker.worker.DemoEvidenceTest#matrizCompletaDeEvidencia`: caso1, caso2a, caso2b,
  caso2c, caso3, caso3b, caso4, caso5-r1, caso5-r2, caso5-r3, caso6-b64, caso6-json,
  caso6-faltan, paso9, translation, hasher).
- Matriz regenerada en esta ejecución: `target/demo/evidencia_casos.json`
  (`"total": 16, "pass": 16, "fail": 0`), `target/demo/EVIDENCIA_POC_LNB.md`.
- Tabla esperada/obtenida: `docs/REPORTE_JD_2026-10-06.md` §"Los 16 escenarios del harness".

## Casos HTTP adicionales

Se ejecutaron 7 casos HTTP contra el JAR en un proceso local separado
(evidencia previa de Henry, 2026-10-06; no re-ejecutados en esta validación):

| Caso | Resultado observado |
|---|---|
| Nuevo válido (caso1) | `SUCCEEDED`, ACK (HTTP 200) |
| Duplicado idéntico (caso2a/réplica) | `IDEMPOTENT`, ACK (HTTP 200) |
| Aggregate no permitido (caso3) | `REJECTED`, ACK (HTTP 200) |
| EventType no permitido (caso3b) | `REJECTED`, ACK (HTTP 200) |
| Base64 inválido (caso6-b64) | `DLQ_QUARANTINED`, ACK (HTTP 200) |
| JSON inválido (caso6-json) | `DLQ_QUARANTINED`, ACK (HTTP 200) |
| Campos ausentes (caso6-faltan) | `DLQ_QUARANTINED`, ACK (HTTP 200) |

Fuente: `docs/evidencia/2026-10-06-henry/http-scenarios.json` (7/7 `pass: true`).

Los casos HTTP son una validación adicional del JAR. No constituyen una demostración
manual de todos los 16 escenarios.

La cuarentena funcional local no acredita el DLQ nativo de Pub/Sub.

## Relación con E2.4

- E2.4 corresponde a una versión anterior de la suite local: 37 pruebas para los mismos
  16 escenarios (antecedente en `docs/evidencia/2026-09-22/README.md`: `mvn test`
  regeneraba `target/demo/` "con esta misma evidencia (37/37 suites)", cubriendo la
  misma matriz de 16 escenarios que hoy da 16/16).
- La evidencia actual amplía la cobertura a 75 pruebas Maven (Bloques 1 y 2; ver commits
  `15e2368`, `95a73e0`, `4d3c3e1`), manteniendo los 16 escenarios.
- Los 7 casos HTTP son complementarios y no forman parte de los 16 escenarios.
- Nota: el identificador "E2.4" como tal no aparece en el repositorio (vive en ClickUp);
  si la definición de ClickUp difiere, dime y ajusto esta sección.

## Evidencia disponible

- Salida Maven (esta ejecución, 75 pruebas): `docs/evidencia/2026-10-10/mvn-verify-output.txt`
- Reportes XML de Surefire: `target/surefire-reports/`
  (10 clases, `Tests run: 75, Failures: 0, Errors: 0, Skipped: 0`)
- Matriz del harness: `target/demo/evidencia_casos.json`, `target/demo/EVIDENCIA_POC_LNB.md`
- Resultados HTTP con respuestas: `docs/evidencia/2026-10-06-henry/http-scenarios.json`
- Logs de monitoreo del proceso: `docs/evidencia/2026-10-06-henry/worker-stdout.log`,
  `docs/evidencia/2026-10-06-henry/worker-stderr.log`,
  `docs/evidencia/2026-10-06-henry/monitoring.json`
- Evidencia previa (Steven, 2026-10-06): `docs/evidencia/2026-10-06/mvn-test-output.txt`,
  `docs/evidencia/2026-10-06/surefire-reports/`
- Script reproducible: `scripts/verify-local-henry.ps1`

> El log de Maven contiene rutas locales de máquina (`C:\Users\...`). Antes de subirlo,
> verifica que no exponga tokens o datos sensibles; si prefieres, sube solo este `.md`
> y referencia el log por ruta.

## Alcance

Esta evidencia acredita la validación local del Worker con Governor MOCK, StateStore
MEMORY y Reporter MOCK.

No acredita integración real en DEV con Pub/Sub, Vertex AI, Sybase/JDBC, VPN, Cloud Run
ni contrato definitivo del reporte.

## Notas E3.2 (fuente única de verdad documental)

- **Advertencia sobre evidencia de Henry:** las rutas
  `docs/evidencia/2026-10-06-henry/http-scenarios.json`,
  `docs/evidencia/2026-10-06-henry/worker-stdout.log`,
  `docs/evidencia/2026-10-06-henry/worker-stderr.log`,
  `docs/evidencia/2026-10-06-henry/monitoring.json` y el script
  `scripts/verify-local-henry.ps1` **no existen en este repositorio** y no pudieron
  verificarse aquí; el contenido aportado se deja tal cual, sin alterar.
- **Alcance E3.2:** `StructuralValidator` queda **fuera** de E3.2 (no se modifica en
  esta tarea).
- **Conteo de tests 75 → 76:** el incremento corresponde a **+1 `@Test`** en
  `WorkerPropertiesTest` (`ackDeadlineMenorQueElPresupuestoDeEntregaFallaAlArrancar()`):
  master tiene 11 `@Test`, la rama `feat/e3.2-vertex-governor` tiene 12.
- **SHA para la prueba cloud:** la evidencia cloud con Vertex real usará un **SHA posterior
  a `825966a`** (commit fijo tras los cambios no bloqueados), nunca `825966a` solo.
