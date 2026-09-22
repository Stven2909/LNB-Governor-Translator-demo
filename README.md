# Worker de la Pagaduría Digital — PoC vertical CONTRACT_PAYMENT_COMMITTED_V0.1 (proyecto LNB)

Worker (módulo de Cloud Run) de la prueba vertical de **LNB / Pagaduría Digital**: recibe el
push de Pub/Sub de un pago confirmado (`PAYMENT_COMMITTED`), lo valida, lo hace pasar por el
**Gobernador** (decisión de negocio) y el **Traductor** (plan SQL de 12 parámetros), persiste en
un mock de **Sybase DES** con idempotencia atómica por `operationId`, cuarentena (DLQ) y
recuperación in-doubt, y reporta a un endpoint (Opción B, mockeado).

Estado de la entrega: **`mvn test` 37/37 en verde** (+ harness E2E de 16 escenarios, 16/16 PASS)
y demo en vivo 7/7 HTTP 200. Cada fase del plan congelado (`lnb-docs/PLAN_IMPLEMENTACION_V0.1.md`)
queda reflejada en `README.md`.

Cómo lo verá una persona del proyecto LNB: este repo es la pieza "adentro" del flujo
`Outbox → Pub/Sub → Worker → Sybase DES`. Todo lo que no es el worker en sí (contrato de Alex,
catálogo, casos de la prueba, plan, reporte de evidencia, fixture SQL) está duplicado en
`lnb-docs/` para que el repo sea autocontenido.

---

## 1. Contenido del repo

```
worker-poc/
├── pom.xml                          # Spring Boot 4.1.1, Java 21, Maven
├── mvnw / mvnw.cmd                  # wrapper Maven (sin instalación necesaria)
├── README.md                        # este documento
├── DEMO.md                          # runbook corto de la demo (tests + live)
├── lnb-docs/                        # esenciales del proyecto para repo autocontenido
│   ├── CONTRACT_EVENTO_V0.1.md          # contrato oficial PAYMENT_COMMITTED de Alex (base)
│   ├── CATALOG_SYNTHETIC_V0.json        # whitelist sintética de referencia V0
│   ├── PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md  # los casos que reproduce el harness
│   ├── PLAN_IMPLEMENTACION_V0.1.md       # PLAN CONGELADO (fases 1-6, decisiones, anexos)
│   ├── PLAN_POC_JAVA_V1.md              # plan de la PoC (fases, decisiones, anexos)
│   ├── MINUTA_ALEX_RESULTADO_V1.md       # minuta de Alex (base del contrato oficial)
│   ├── AMBIENTE_DEV_LNB.md              # ambiente/seguridad del entorno DEV
│   ├── REPORTE_EVIDENCIA_POC_LNB.md     # reporte con índices a la evidencia
│   └── FIXTURE_SYNTHETIC_DEV.sql        # DDL sintético de las 2 tablas (DEV)
├── docs/evidencia/                  # snapshot congelado de evidencia (ver su README)
├── scripts/                         # demo en vivo (arranque/jar, POSTs, stop por :8080)
│   ├── demo-live-start.ps1
│   ├── demo-live-run.ps1
│   └── demo-live-stop.ps1
└── src/
    ├── main/java/com/pagaduriasintetica/worker/   # código del worker (ver §3)
    ├── main/resources/application.properties      # config trivial (solo nombre de app)
    ├── main/resources/catalog/CATALOG_PAYMENT_COMMITTED_V0.1.json  # catálogo en el classpath
    └── test/java/com/pagaduriasintetica/worker/   # suites + harness de evidencia (ver §6)
```

> **`target/` NO se sube al repo** (`.gitignore`): es regenerable con `mvn test` y contiene el
> jar, la evidencia en vivo y una copia fresca de la misma.

---

## 2. Contexto de negocio y arquitectura

El proyecto LNB definió para la Pagaduría Digital una transición de **Sybase legacy hacia un
flujo moderno**: el evento de negocio sale del outbox del sistema actual, se publica en Pub/Sub
y llega a este worker (módulo Cloud Run). El worker **nunca escribe a ciegas**: cada paso decide
contra un **catálogo-whitelist** (`CATALOG_PAYMENT_COMMITTED_V0.1.json`, reglas visibles, sin
hidden rules) y toda escritura a Sybase es un plan SQL generado por el Traductor con columnas y
parámetros validados campo a campo.

En la PoC los cuatro actores externos están **mockeados de forma determinista**:

| Actor externo (prod LNB)         | En esta PoC                                        |
|----------------------------------|----------------------------------------------------|
| Pub/Sub (suscripción)            | POST HTTP simulado a `/push` (envelope real)       |
| Gobernador (Vertex AI + LLM)     | `MockGovernor` (aprueba el plan del catálogo; `setOverride` inyecta alucinación/rechazo) |
| Traductor                        | `DeterministicTranslator` (limitado al catálogo)   |
| Sybase DES                       | `InMemoryStateStore` (mock de 2 tablas)            |
| JDBC real                        | `FixtureJdbcExecutor` (CONFIRMED / TEMPORARY_FAILURE / UNKNOWN) |
| Endpoint de reporte              | `MockResultReporter` (SUCCEEDED / CONFLICT 409 / FAILED) |

Pipeline canónico (también es el flujo de `WorkerService.handleRaw`):

```
POST /push  (envelope Pub/Sub, message.data en Base64)
   │
   ▼
PushController ──► WorkerService.handleRaw
   │                 • body vacío → DLQ
   ▼
decode Base64 message.data ──► PaymentCommittedEvent (Jackson 3)
   │                 • Base64 inválido → DLQ
   ▼
StructuralValidator.validate    (siempre DLQ si falla el esquema)
   │
   ▼
Catálogo PRE-FILTRO: allows(aggregateType, eventType) → REJECTED SIN reservar (regla: el
   │                pre-filtro va ANTES de la reserva atómica y ANTES de Vertex)
   ▼
workerTraceId = UUID;  PayloadHasher → PAYLOAD_HASH canónico (SHA-256, contrato)
   ▼
store.reserveAtomic (INSERT de PK operationId, putIfAbsent = atómico)
   │                 • DuplicateOperationException → handleRedelivery (matriz de estados)
   ▼
invariante monetaria (validationRules: netAmount == gross - withholding, BigDecimal.compareTo,
   │                SIN LLM y SIN recalcular montos) → Violada ⇒ REJECTED antes de Vertex
   ▼
Governor.decide(new GovernorInput(event, workerTraceId)) ──► GovernorContract
   │                 • REJECTED → REJECTED (ACK, no va a DLQ)
   ▼
Catalog.validateGovernor (whitelist exacta del output del LLM) → DLQ si alucina
   ▼
Translator.translate(new TranslatorInput(event, governor, hash, workerTraceId)) → 12 params
   │                (8 de negocio del field_mapping + OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH)
   ▼
validateTranslation (guard: INSERT INTO <tabla del catálogo> exacta, 12 params,
   │                columnas ⊆ whitelist + técnicas) → TRANSLATION_ERROR si inventa columnas
   ▼
jdbc.execute(PreparedStatementSpec) ──► FixtureJdbcExecutor
   │                 CONFIRMED → JDBC_COMMITTED (fila en SYNTHETIC_PAYMENTS)
   │                 TEMPORARY_FAILURE → RETRYABLE (sin fila)
   │                 UNKNOWN → commitPaymentInDoubt → IN_DOUBT (fila persistida, resultado incierto)
   ▼
ResultReporter.report(OperationResult)
   │                 SUCCEEDED → SUCCEEDED + ACK
   │                 CONFLICT (409) → SUCCEEDED + ACK (ya reportado; NUNCA re-ejecuta JDBC)
   │                 FAILED → RETRYABLE + NACK (redelivery reintenta SOLO el reporte)
   ▼
ACK (HTTP 200)  — solo después de persistir y/o clasificar la evidencia
```

Reglas de ACK/NACK hacia Pub/Sub (contrato de LNB):

| Respuesta | HTTP | Significado |
|---|---|---|
| **ACK** | 200 | El mensaje ya quedó persistido o clasificado (SUCCEEDED, IDEMPOTENT, REJECTED, DLQ...). No se reenvía. |
| **NACK** | 500 | Pub/Sub debe reintentar (RETRYABLE por fallo temporal, evento en vuelo). |

**Redelivery sin segunda ejecución JDBC** (recuperación, regla de oro del plan): si la fila
`SYNTHETIC_PAYMENTS` ya existe con el mismo PAYLOAD_HASH, la redelivery **nunca re-ejecuta el
JDBC**; según el estado: `JDBC_COMMITTED`/`REPORT_PENDING`/`RETRYABLE` (con fila) → solo
reintentar el reporte; `PROCESSING` (con fila, crash post-commit) o `IN_DOUBT` → conciliación a
SUCCEEDED por hash; `SUCCEEDED` + mismo hash → IDEMPOTENT; mismo `operationId` con **otro** hash
→ DLQ por colisión de PK.

---

## 3. Estructura del código: qué hace cada parte

Paquete base: `com.pagaduriasintetica.worker` (modular monolith), separado por **responsabilidad**.

### `contract/` — tipos del contrato (records + enums, sin lógica)
| Clase | Qué es |
|---|---|
| `PushEnvelope` / `PushMessage` | Envelope HTTP de Pub/Sub (`message.data` Base64, `messageId`, `publishTime`, `deliveryAttempt`). |
| `PaymentCommittedEvent` | Evento de negocio oficial (Alex): `eventId, eventType(PAYMENT_COMMITTED), aggregateType(prizes.payment), aggregateId, eventVersion(=1), destinationSystem(SYBASE), occurredAt, correlationId, operationId, operationData`. |
| `OperationData` | Datos de negocio (8 campos): `paymentId, claimId, status, paymentMethod, grossAmount, withholdingAmount, netAmount, currency` (montos `BigDecimal`). |
| `OperationContext` | `workerTraceId` (generado por el Worker) + `correlationId` (de Alex); para logs/Vertex/`sync_attempt.trace_id`. |
| `GovernorInput` / `GovernorContract` | Entrada al Gobernador `(evento, workerTraceId)`; salida `(contract_version, decision, operationId, traceId, reason, target_table, required_fields, field_mapping, value_rules, catalog_version, violations)`. |
| `TranslatorInput` / `TranslatorResult` | Entrada al Traductor `(evento, GovernorContract, payloadHash, workerTraceId)`; salida `sql_template` + `parameters` (12) + `preview_sql`. |
| `PreparedStatementSpec` | Plan ejecutable del JDBC: `sql_template, target_table, parameters, operationId, payloadHash`. |
| `JdbcExecutionResult` / `JdbcOutcome` | Resultado del JDBC: `CONFIRMED / TEMPORARY_FAILURE / UNKNOWN`. |
| `OperationResult` / `ReportResult` / `ReportOutcome` | Reporte al endpoint: `SUCCEEDED / CONFLICT / FAILED`. |
| `ProcessingOutcome` | Resultado hacia Pub/Sub: `ack` + `status` + `operationId` + `reason`. |
| `OperationStatus` | `PROCESSING / JDBC_COMMITTED / REPORT_PENDING / SUCCEEDED / REJECTED / RETRYABLE / IN_DOUBT / IDEMPOTENT / TRANSLATION_ERROR / DLQ_QUARANTINED`. |
| `OperationState` | Fila de `WORKER_OPERATION_STATE` (PK operationId + payloadHash + estado + attempts + decisión). Inmutable (`with*`). |
| `PaymentRow` | Fila de `SYNTHETIC_PAYMENTS` (operationId + payloadHash + target_table + parámetros). |

### `catalog/`
| Clase | Qué es |
|---|---|
| `Catalog` | Carga `catalog/CATALOG_PAYMENT_COMMITTED_V0.1.json` del classpath (whitelist). Triple rol: pre-filtro (`allows(aggregateType, eventType)`), validación exacta del output del Gobernador (`validateGovernor`, incluida la comparación de `catalog_version`) y única fuente de tabla/columnas para el Traductor (`targetTable`, `fieldMapping`, `requiredFields`, `valueRules`, `validationRules`). |

### `governor/`
| Clase | Qué es |
|---|---|
| `Governor` | Interfaz de decisión (en prod: Vertex AI). |
| `MockGovernor` | Default APPROVED con el plan exacto del catálogo; `setOverride` inyecta comportamientos (alucinar tabla → DLQ, rechazo de negocio → REJECTED). |

### `translator/`
| Clase | Qué es |
|---|---|
| `Translator` | Interfaz: TranslatorInput → TranslatorResult. |
| `DeterministicTranslator` | Genera `INSERT INTO <target> (8 columnas de negocio por field_mapping + OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?, ×12)`, aplicando las `value_rules` autorizadas (status/method/moneda). Solo columnas del catálogo: cualquier columna libre = TRANSLATION_ERROR (regla *no_invented_columns*). |

### `worker/` — orquestador, estado, JDBC y reporte
| Clase | Qué es |
|---|---|
| `WorkerService` | Orquesta todo el pipeline (ver §2). Decide ACK/NACK y los estados finales. Regla inviolable: el Gobernador **nunca** se invoca antes de reservar `operationId`, y la invariante monetaria se evalúa sin LLM. |
| `PushController` | `POST /push`: body crudo → `handleRaw`, HTTP 200 (ACK) / 500 (NACK). |
| `StructuralValidator` | Esquema del evento (no reglas de negocio): eventVersion, IDs, ISO-8601, destinationSystem SYBASE, montos > 0, longitudes. |
| `PayloadHasher` | Hash canónico SHA-256 sobre `OperationData` (TreeMap + `BigDecimal.setScale(2)`). Constantes `CONTRACT_VERSION`, `EVENT_TYPE`, `AGGREGATE_TYPE`, `EVENT_VERSION`. |
| `OperationStateMachine` | Política de transiciones centralizada; toda mutación pasa por ella; transiciones inválidas (p. ej. SUCCEEDED→PROCESSING) lanzan `IllegalStateException`. |
| `StateStore` / `InMemoryStateStore` | Interfaz y mock de Sybase: `WORKER_OPERATION_STATE` + `SYNTHETIC_PAYMENTS` + auditoría de cuarentena. `reserveAtomic` usa `putIfAbsent` (= INSERT de PK; prohibido check-then-act). Helpers de fixture: `seedDoubtful`, `seedInDoubt`, `seedJdbcCommitted`, `commitPayment`, `commitPaymentInDoubt`. |
| `DuplicateOperationException` | Señala colisión de PK → redelivery por matriz de estados. |
| `JdbcExecutor` / `FixtureJdbcExecutor` | Interfaz del JDBC y ejecutor del fixture (`executeFixtureTransaction()`): CONFIRMED/TEMPORARY_FAILURE/UNKNOWN; `executeCount()` y `setForcedOutcome` para pruebas de no-reejecución. |
| `ResultReporter` / `MockResultReporter` | Interfaz del endpoint de reporte (Opción B) y su mock: SUCCEEDED/CONFLICT/FAILED con `setOverride`. |

---

## 4. Cómo se conectan las piezas

Grafo real de Spring de la prueba vertical (beansa reales, contexto completo):

```
WorkerPocApplication
   └─ PushController ──► WorkerService
                          ├─ ObjectMapper (Jackson 3, autoconfig de Boot)
                          ├─ PayloadHasher
                          ├─ StructuralValidator
                          ├─ Catalog            (carga el JSON del classpath al constructor)
                          ├─ Governor  ──► MockGovernor
                          ├─ Translator ──► DeterministicTranslator
                          ├─ StateStore ──► InMemoryStateStore (2 tablas sintéticas)
                          ├─ OperationStateMachine (todas las transiciones)
                          ├─ JdbcExecutor ──► FixtureJdbcExecutor (misma base sintética)
                          └─ ResultReporter ──► MockResultReporter
```

Los records del `contract/` son los "envelopes" entre etapas: `PushEnvelope` (HTTP)
→ `PaymentCommittedEvent` (dominio) → `GovernorContract` (salida del Gobernador) →
`TranslatorResult` (SQL) → `PreparedStatementSpec` (JDBC) → `OperationResult` (reporte) →
`OperationState`/`PaymentRow` (persistencia). Al cambiar un actor (p. ej. Gobernador por su
cliente Vertex AI real) solo hay que dar otra implementación de la interfaz: el resto del
pipeline no cambia.

**Particularidad técnica:** el proyecto usa **Jackson 3** (Spring Boot 4), por eso los imports
son `tools.jackson.databind.*` y no `com.fasterxml.jackson.*`.

---

## 5. Estados del contrato y transiciones

Estados del contrato (`OperationStatus`):

| Estado | Cuándo se emite | ACK/NACK |
|---|---|---|
| `PROCESSING` | Reserva atómica hecha, pipeline en curso | — |
| `JDBC_COMMITTED` | `executeFixtureTransaction()` confirmó el INSERT (fila persistida) | — |
| `REPORT_PENDING` | JDBC confirmado, enviando reporte al endpoint | — |
| `SUCCEEDED` | Reporte OK (o 409 ya reportado, o in-doubt conciliado por hash) | ACK |
| `IDEMPOTENT` | Mismo operationId + mismo PAYLOAD_HASH ya procesado | ACK |
| `RETRYABLE` | Fallo JDBC temporal (sin fila), fallo de reporte (con fila) o evento en vuelo | NACK |
| `IN_DOUBT` | Resultado JDBC indeterminado (UNKNOWN): commit posiblemente aplicado | NACK (redelivery concilia) |
| `REJECTED` | Pre-filtro del catálogo (sin Vertex) o invariante monetaria o rechazo del Gobernador (paso 9). No va a DLQ. | ACK |
| `DLQ_QUARANTINED` | Base64/JSON malformados, alucinación del Gobernador, colisión de PK (otro hash), in-doubt con hash inconsistente | ACK |
| `TRANSLATION_ERROR` | El Traductor inventa columnas / SQL fuera de whitelist (guard `validateTranslation`) | ACK |

Política de transiciones (`OperationStateMachine`):

```
PROCESSING      → JDBC_COMMITTED, REJECTED, RETRYABLE, IN_DOUBT, TRANSLATION_ERROR, DLQ_QUARANTINED
RETRYABLE       → PROCESSING (reproceso), REPORT_PENDING (solo reporte), DLQ_QUARANTINED
JDBC_COMMITTED  → REPORT_PENDING, SUCCEEDED, RETRYABLE, IN_DOUBT, DLQ_QUARANTINED
REPORT_PENDING  → SUCCEEDED, RETRYABLE, DLQ_QUARANTINED
IN_DOUBT        → SUCCEEDED (hash igual), RETRYABLE, DLQ_QUARANTINED (hash distinto)
SUCCEEDED / REJECTED / TRANSLATION_ERROR / DLQ_QUARANTINED  → terminales
```

Ejemplos de inválidas: `SUCCEEDED → PROCESSING`, `PROCESSING → SUCCEEDED` (la recuperación
in-doubt pasa por `IN_DOUBT`, no por el salto directo).

La regla de oro es de LNB: **ACK solo cuando ya hay evidencia persistida o clasificada**; así un
duplicado nunca causa doble pago (mitigado además por el "nunca re-ejecutar JDBC" en redelivery).

---

## 6. Pruebas y evidencia

| Suite | Qué cubre |
|---|---|
| `WorkerPocApplicationTests` | Smoke: el contexto Spring completo arranca. |
| `WorkerPipelineTest` (24) | E2E con beans reales: los 6 casos de LNB + subcasos in-doubt + las ~15 pruebas nuevas del plan (Fase 5). |
| `BranchingCoverageTest` (4) | Cobertura por rama (Mockito puro, sin ApplicationContext): pre-filtro sin reservar, invariante antes del Gobernador, rechazo del Gobernador, traducción inventada sin JDBC. |
| `OperationStateMachineTest` (4) | Política de transiciones: válidas, redelivery desde JDBC_COMMITTED, terminales, salto inválido. |
| `PayloadHasherTest` (3) | Hash canónico del contrato, normalización `200/150` y equivalencia decimal `1.10 == 1.1`. |
| `DemoEvidenceTest` | **Harness E2E de la demo**: matriz de 16 escenarios contra el pipeline real y exporta la evidencia. |
| `TestEnvelopeFactory` | Helpers de test (fixture único + `HASH_OP001`, no es una suite). |
| `EvidenceWriter` | Exporta la evidencia a `target/demo/`. |

Los 16 escenarios del harness: caso1 → SUCCEEDED; caso2a → IDEMPOTENT; caso2b → RETRYABLE (en
vuelo); caso2c → DLQ (PK collision, ACK DLQ sin degradar el SUCCEEDED); caso3 → REJECTED
(pre-filtro del catálogo, sin reservar); caso3b → REJECTED (eventType no permitido); caso4 → DLQ
(Gobernador alucina tabla); caso5 r1/r2/r3 → in-doubt recuperado / retry reprocesado / in-doubt
con hash distinto (DLQ); caso6 ×3 → DLQ (Base64 corrupto, JSON malformado, campos faltantes);
paso9 → REJECTED (decisión del Gobernador); translation → TRANSLATION_ERROR (columna
inventada); hasher → PASS (hash del contrato). Cada escenario registra su expediente y el
`@AfterAll` exporta `evidencia_casos.json`, `EVIDENCIA_POC_LNB.md` y los envelopes reutilizables.

### Reproducir la evidencia
```powershell
mvn test
```
Regenera `target/demo/` (gitignored). Snapshot congelado de la entrega en `docs/evidencia/`.

---

## 7. Demo en vivo (HTTP)

```powershell
mvn -DskipTests package
.\scripts\demo-live-start.ps1     # arranca el jar en :8080 y espera health UP
.\scripts\demo-live-run.ps1       # POSTea target\demo\envelopes\live\*.json a /push
.\scripts\demo-live-stop.ps1      # detiene el worker por el listener de :8080
```
Resultado esperado: 7/7 HTTP 200 (`SUCCEEDED`, `IDEMPOTENT`, `REJECTED` ×2, `DLQ_QUARANTINED`
×3); también se guarda `target/demo/demo-live-run.json`. Más detalle en `DEMO.md`.

---

## 8. Requisitos y particularidades del entorno

- **Java 21** y **Maven 3.9+** (o el wrapper `mvnw`). Spring Boot 4.1.1 → **Jackson 3**
  (`tools.jackson`).
- En Windows PowerShell, los scripts de live usan `Start-Process` y matan el worker por el
  **listener de :8080** (no por PID), porque el PID registrado puede ser el del shim y no el del
  JVM.
- Puerto: `8080`. Health check: actuator (`/actuator/health`).

---

## 9. Limitaciones declaradas de la PoC

- Sybase/`InMemoryStateStore`, Gobernador (`MockGovernor`), Traductor y el endpoint de reporte
  son deterministas/mock: **no hay JDBC real, ni Pub/Sub, ni Vertex, ni HTTP al endpoint**.
  `FixtureJdbcExecutor` y `MockResultReporter` son los puntos de sustitución.
- La **atomicidad local del fixture NO demuestra la atomicidad Sybase-almacenamiento del
  Worker** en integración real (un UNKNOWN real requiere decidir entre commit y no-commit).
- El escenario "evento en vuelo" es **simulado** (registro PROCESSING pre-sembrado), no
  concurrencia real de hilos.
- El catálogo es el establecido por Alex (`CATALOG_PAYMENT_COMMITTED_V0.1.json`,
  `SYNTHETIC_PAYMENTS` placeholder de `prize.payment`); el fixture SQL es DDL de DEV.
- El escenario de hash de contrato es una función pura (sin HTTP).

Puntos de sustitución en producción: `Governor` (Vertex AI), `Translator`, `StateStore` (JDBC
real vía jConnect) y `ResultReporter` (HTTP al endpoint oficial) — el resto del pipeline queda
intacto.

## 10. Referencias

- `lnb-docs/CONTRACT_EVENTO_V0.1.md` — contrato oficial PAYMENT_COMMITTED de Alex (base).
- `lnb-docs/PLAN_IMPLEMENTACION_V0.1.md` — plan CONGELADO que ejecuta esta implementación.
- `lnb-docs/MINUTA_ALEX_RESULTADO_V1.md` — minuta/decisiones de Alex.
- `lnb-docs/AMBIENTE_DEV_LNB.md` — ambiente y seguridades del DEV.
- `lnb-docs/PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md` — casos originales que reproduce el harness.
- `lnb-docs/PLAN_POC_JAVA_V1.md` — plan de la PoC Java (fases, decisiones, anexos).
- `lnb-docs/REPORTE_EVIDENCIA_POC_LNB.md` — reporte con índices a toda la evidencia.
- `src/main/resources/catalog/CATALOG_PAYMENT_COMMITTED_V0.1.json` — whitelist real del worker.
- `docs/evidencia/README.md` — cómo leer el snapshot congelado.
- `DEMO.md` — runbook corto de la demo.