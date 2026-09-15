# Worker de la Pagaduría Digital — PoC vertical CONTRACT_SYNTHETIC_V0 (proyecto LNB)

Worker (módulo de Cloud Run) de la prueba vertical de **LNB / Pagaduría Digital**: recibe el
push de Pub/Sub de un pago sintético, lo valida, lo hace pasar por el **Gobernador** (decisión
de negocio) y el **Traductor** (generación del plan SQL), y persiste el resultado en un mock de
**Sybase DES** con idempotencia, cuarentena (DLQ) y recuperación in-doubt.

Estado de la entrega: **`mvn test` 20/20** (19 suites + harness E2E de 16 escenarios) y demo en
vivo 7/7 HTTP 200. Evidencia congelada en `docs/evidencia/2026-09-15/`.

Cómo lo verá una persona del proyecto LNB: este repo es la pieza "adentro" del flujo
`Outbox → Pub/Sub → Worker → Sybase`. Todo lo que no es el worker en sí (contrato, catálogo,
casos de la prueba, plan, reporte de evidencia, fixture SQL) está duplicado en `lnb-docs/` para
que el repo sea autocontenido.

---

## 1. Contenido del repo

```
worker-poc/
├── pom.xml                          # Spring Boot 4.1.1, Java 21, Maven
├── mvnw / mvnw.cmd                  # wrapper Maven (sin instalación necesaria)
├── README.md                        # este documento
├── DEMO.md                          # runbook corto de la demo (tests + live)
├── lnb-docs/                        # esenciales del proyecto copiados para que el repo sea autocontenido
│   ├── CONTRACT_SYNTHETIC_V0.md         # contrato V0: estados, hash, pasos, DLQ
│   ├── CATALOG_SYNTHETIC_V0.json        # whitelist de entidad/operación/tabla/columnas
│   ├── PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md  # los casos que reproduce el harness
│   ├── PLAN_POC_JAVA_V1.md              # plan de la PoC (fases, decisiones, anexos)
│   ├── REPORTE_EVIDENCIA_POC_LNB.md     # reporte con índices a la evidencia
│   └── FIXTURE_SYNTHETIC_DEV.sql        # DDL sintético de las 2 tablas (DEV)
├── docs/evidencia/2026-09-15/       # snapshot congelado de la evidencia (ver su README)
├── scripts/                         # demo en vivo (arranque/jar, POSTs, stop por :8080)
│   ├── demo-live-start.ps1
│   ├── demo-live-run.ps1
│   └── demo-live-stop.ps1
└── src/
    ├── main/java/com/pagaduriasintetica/worker/   # código del worker (ver §3)
    ├── main/resources/application.properties      # config trivial (solo nombre de app)
    ├── main/resources/catalog/CATALOG_SYNTHETIC_V0.json  # catálogo en el classpath
    └── test/java/com/pagaduriasintetica/worker/   # suites + harness de evidencia (ver §6)
```

> **`target/` NO se sube al repo** (`.gitignore`): es regenerable con `mvn test` y contiene el
> jar, la evidencia en vivo y una copia fresca de la misma.

---

## 2. Contexto de negocio y arquitectura

El proyecto LNB definió para la Pagaduría Digital una transición de **Sybase legacy hacia un
flujo moderno**: el evento de negocio sale del outbox del sistema actual, se publica en Pub/Sub
y llega a este worker. El worker no escribe nunca "a ciegas": cada paso decide contra un
**catálogo-whitelist** (reglas visibles, sin hidden rules) y toda escritura a Sybase es un SQL
generado por el Traductor y validado campo a campo.

En la PoC los tres actores externos están **mockeados de forma determinista**:

| Actor externo (prod LNB)    | En esta PoC                                    |
|-----------------------------|------------------------------------------------|
| Pub/Sub (suscripción)        | POST HTTP simulado a `/push` (envelope real)  |
| Gobernador (Vertex AI + LLM) | `MockGovernor` (por defecto aprueba el plan del catálogo; `setOverride` inyecta alucinación/rechazo) |
| Traductor                   | `DeterministicTranslator` (limitado al catálogo) |
| Sybase DES                  | `InMemoryStateStore` (mock de 2 tablas)       |

Pipeline completo (también es el flujo de `WorkerService.handleRaw`):

```
POST /push  (envelope Pub/Sub, message.data en Base64)
   │
   ▼
PushController ──► WorkerService.handleRaw
   │                 • body vacío → DLQ
   ▼
decode Base64 message.data ──► SyntheticEvent (Jackson 3)
   │                 • Base64 inválido → DLQ
   ▼
StructuralValidator.validate   (siempre DLQ si falla el esquema)
   ▼
PayloadHasher.hash ──► PAYLOAD_HASH canónico (SHA-256, contrato §2.1)
   ▼
store.reserve (INSERT de PK operationId, atómico)
   │                 • DuplicateOperationException → matriz de estados (idempotencia/in-doubt)
   ▼
Catálogo pre-filtro: allows(entity, operation, eventType)  → REJECTED sin invocar Vertex
   ▼
Governor.decide(event) ──► GovernorContract
   │                 • REJECTED → REJECTED (ACK, no va a DLQ)
   ▼
Catalog.validateGovernor (whitelist exacta de output del LLM)  → DLQ si alucina
   ▼
Translator.translate(event, governor, hash) ──► TranslatorResult (SQL + 8 params)
   ▼
validateTranslation (guard: tabla exacta, 8 cols, columnas ⊆ whitelist)  → TRANSLATION_ERROR
   ▼
store.commitInsert(SYNTHETIC_PAYMENTS + WORKER_OPERATION_STATE=SUCCEEDED)
   ▼
ACK (HTTP 200)  — solo después de persistir la evidencia
```

Reglas de ACK/NACK hacia Pub/Sub (contrato de LNB):

| Respuesta | HTTP | Significado |
|---|---|---|
| **ACK** | 200 | El mensaje ya quedó persistido o clasificado (SUCCEEDED, IDEMPOTENT, REJECTED, DLQ...). No se reenvía. |
| **NACK** | 500 | Pub/Sub debe reintentar (RETRYABLE, evento en vuelo/redelivery). |

---

## 3. Estructura del código: qué hace cada parte

Paquete base: `com.pagaduriasintetica.worker` (modular monolith). Se separa por **responsabilidad**,
no por capa técnica.

### `contract/` — tipos del contrato (records + enums, sin lógica)
| Clase | Qué es |
|---|---|
| `PushEnvelope` / `PushMessage` | Envelope HTTP de Pub/Sub (subscription, message.data Base64, messageId, publishTime). |
| `SyntheticEvent` | Evento decodificado: contract_version, eventId, eventType, operationId, traceId, occurredAt, operation, entity + payload. |
| `SyntheticPayload` | Datos de negocio: claimId, amount (decimal), operationDate, beneficiary. |
| `ProcessingOutcome` | Resultado hacia Pub/Sub: `ack` + `status` + `operationId` + `reason`. |
| `OperationStatus` | `PROCESSING / SUCCEEDED / REJECTED / IDEMPOTENT / RETRYABLE / DLQ_QUARANTINED / TRANSLATION_ERROR`. |
| `GovernorDecision` | Salida del Gobernador: `APPROVED` o `REJECTED`. |
| `GovernorContract` | DTO de salida del Gobernador: decisión + plan de escritura (target_table, field_mapping, required_fields, value_rules). |
| `TranslatorResult` | Salida del Traductor: `sql_template`, `parameters` (8), `preview_sql`. |
| `OperationState` | Fila de `WORKER_OPERATION_STATE` (PK operationId + payloadHash, estado, attempts, decisión). Inmutable (`with*`). |
| `PaymentRow` | Fila de `SYNTHETIC_PAYMENTS` (operationId + payloadHash + target_table + parámetros). |

### `catalog/`
| Clase | Qué es |
|---|---|
| `Catalog` | Carga `catalog/CATALOG_SYNTHETIC_V0.json` del classpath y es la **whitelist**. Triple rol: pre-filtro (`allows`), validación exacta del output del Gobernador (`validateGovernor`) y única fuente de tabla/columnas para el Traductor (`targetTable`, `fieldMapping`, `requiredFields`). |

### `governor/`
| Clase | Qué es |
|---|---|
| `Governor` | Interfaz de decisión (en prod: Vertex AI). |
| `MockGovernor` | Default APPROVED con el plan exacto del catálogo; `setOverride` inyecta comportamientos para probar ramas (alucinar tabla → DLQ, rechazo de negocio → REJECTED). |

### `translator/`
| Clase | Qué es |
|---|---|
| `Translator` | Interfaz: evento + GovernorContract + payloadHash → TranslatorResult. |
| `DeterministicTranslator` | Genera `INSERT INTO <target> (4 columnas de negocio + OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) VALUES (?,×8)`. Solo columnas del catálogo: cualquier columna libre = TRANSLATION_ERROR (regla *no_invented_columns*). |

### `worker/` — el orquestador y el estado
| Clase | Qué es |
|---|---|
| `WorkerService` | Orquesta todo el pipeline (ver diagrama §2). Es la única clase que decide ACK/NACK y los estados finales. |
| `PushController` | `POST /push`: recibe el body crudo, llama a `workerService.handleRaw`, devuelve `{ack,status,operationId,reason}` con HTTP 200 (ACK) o 500 (NACK). |
| `StructuralValidator` | Esquema mínimo del evento (no reglas de negocio): contract_version, IDs, fechas ISO, monto > 0, longitudes. |
| `PayloadHasher` | Hash canónico del payload (formato sin espacios, moneda a 2 decimales). Constantes `CONTRACT_VERSION`, `EVENT_TYPE`, `OPERATION`, `ENTITY`. |
| `StateStore` | Interfaz de la BD. |
| `InMemoryStateStore` | Mock de Sybase: `WORKER_OPERATION_STATE` (control/idempotencia) + `SYNTHETIC_PAYMENTS` (pago) + auditoría de cuarentena. `putIfAbsent` = INSERT de la PK (prohibido check-then-act). |
| `DuplicateOperationException` | Señala colisión de PK: duplicado o evento en vuelo, resuelto por matriz de estados. |

### `WorkerPocApplication`
Entry point del Cloud Run (módulo Spring Boot).

---

## 4. Cómo se conectan las piezas

A diferencia de caso→mock aislado, la prueba vertical arma el **grafo real de Spring**:

```
WorkerPocApplication
   └─ PushController ──► WorkerService
                          ├─ ObjectMapper (Jackson 3, autoconfig de Boot)
                          ├─ PayloadHasher
                          ├─ StructuralValidator
                          ├─ Catalog            (carga el JSON del classpath al constructor)
                          ├─ Governor  ──► MockGovernor
                          ├─ Translator ──► DeterministicTranslator
                          └─ StateStore ──► InMemoryStateStore
```

Los records del `contract/` son los "envelopes" que viajan entre etapas: `PushEnvelope` (HTTP)
→ `SyntheticEvent` (dominio) → `GovernorContract` (salida del Gobernador) → `TranslatorResult`
(SQL) → `OperationState`/`PaymentRow` (persistencia). Al cambiar un actor (p. ej. Gobernador por
su cliente Vertex AI real) solo hay que dar otra implementación de `Governor`: el resto del
pipeline no cambia.

**Particularidad técnica:** el proyecto usa **Jackson 3** (Spring Boot 4), por eso los imports
son `tools.jackson.databind.*` y no `com.fasterxml.jackson.*`.

---

## 5. Estados del contrato: qué significa cada uno

| Estado | Cuándo se emite | ACK/NACK |
|---|---|---|
| `PROCESSING` | Reserva atómica hecha, pipeline en curso | — |
| `SUCCEEDED` | Pipeline completo y pago persistido (o in-doubt promovido) | ACK |
| `IDEMPOTENT` | Mismo operationId + mismo PAYLOAD_HASH ya procesado | ACK |
| `RETRYABLE` | Evento en vuelo (PROCESSING de otro delivery) → redelivery | NACK |
| `REJECTED` | Pre-filtro del catálogo (sin Vertex) o rechazo del Gobernador (paso 9). No va a DLQ. | ACK |
| `DLQ_QUARANTINED` | Basura/desviación: Base64/JSON malformados, PK collision, alucinación del Gobernador, in-doubt con hash inconsistente | ACK |
| `TRANSLATION_ERROR` | El Traductor inventa columnas/falta el SQL válido (guard `validateTranslation`) | ACK |

La regla de oro es de LNB: **ACK solo cuando ya hay evidencia persistida** (salvo el NACK
controlado de RETRYABLE); así un duplicado nunca causa doble pago.

---

## 6. Pruebas y evidencia

| Suite | Qué cubre |
|---|---|
| `WorkerPocApplicationTests` | Smoke: el contexto Spring completo arranca. |
| `WorkerPipelineTest` | E2E con beans reales: los 6 casos de LNB por comportamiento. |
| `BranchingCoverageTest` | Cobertura por rama (Mockito puro, sin ApplicationContext): pre-filtro, matriz de estados, in-doubt, alucinación, traducción inventada. |
| `PayloadHasherTest` | Verificación del hash canónico del contrato. |
| `DemoEvidenceTest` | **Harness E2E de la demo**: corre la matriz de 16 escenarios contra el pipeline real y exporta la evidencia. |
| `TestEnvelopeFactory` | Helpers de test: fabrica eventos/envelopes válidos (no es una suite). |
| `EvidenceWriter` | Exporta la evidencia a `target/demo/`. |

Los 16 escenarios del harness (orden de la narrativa de demo): caso1 → SUCCEEDED; caso2a →
IDEMPOTENT; caso2b → RETRYABLE (en vuelo); caso2c → DLQ (PK collision); caso3 → REJECTED
(pre-filtro); caso3b → REJECTED (DELETE no permitida); caso4 → DLQ (Gobernador alucina tabla);
caso5 r1/r2/r3 → in-doubt recuperado / retry reprocesado / in-doubt con hash distinto (DLQ);
caso6 ×3 → DLQ (Base64 corrupto, JSON malformado, campos faltantes); paso9 → REJECTED
(Gobernador); translation → TRANSLATION_ERROR (columna inventada); hasher → PASS (hash de
contrato). Cada `casoXX_*` registra en un expediente todo lo que pasó y el `@AfterAll` exporta
`evidencia_casos.json`, `EVIDENCIA_POC_LNB.md` y los envelopes reutilizables.

### Reproducir la evidencia
```powershell
mvn test
```
Regenera `target/demo/` (gitignored). La evidencia congelada de esta entrega está en
`docs/evidencia/2026-09-15/` (lea su `README.md`).

---

## 7. Demo en vivo (HTTP)

```powershell
mvn -DskipTests package
.\scripts\demo-live-start.ps1     # arranca el jar en :8080 y espera health UP
.\scripts\demo-live-run.ps1       # POSTea target\demo\envelopes\live\*.json a /push
.\scripts\demo-live-stop.ps1      # detiene el worker por el listener de :8080
```
Resultado esperado: 7/7 HTTP 200 (`SUCCEEDED`, `IDEMPOTENT`, `REJECTED`×2, `DLQ_QUARANTINED`×3);
también se guarda `target/demo/demo-live-run.json`. Más detalle en `DEMO.md`.

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

- Sybase/`InMemoryStateStore`, Gobernador (`MockGovernor`) y Traductor son deterministas/mock:
  **no hay JDBC ni Pub/Sub ni Vertex reales**.
- El escenario "evento en vuelo" es **simulado** (registro PROCESSING pre-sembrado), no
  concurrencia de hilos real.
- El catálogo subido es **sintético V0** (`synthetic_payment`), no el catálogo real de LNB; el
  fixture SQL es un placeholder de `prize.payment`/`prize.prize_claim`.
- El escenario de hash de contrato es una función pura (sin HTTP).

Puntos de sustitución en producción: nueva implementación de `Governor` (Vertex AI), de
`Translator`, y de `StateStore` (JDBC real vía jConnect) — el resto del pipeline queda intacto.

## 10. Referencias

- `lnb-docs/CONTRACT_SYNTHETIC_V0.md` — contrato de la PoC (estados, hash, DLQ, pasos).
- `lnb-docs/CATALOG_SYNTHETIC_V0.json` — whitelist de entidad → operación → tabla → columnas.
- `lnb-docs/PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md` — casos originales que reproduce el harness.
- `lnb-docs/PLAN_POC_JAVA_V1.md` — plan completo de la PoC Java (fases, decisiones, anexos).
- `lnb-docs/REPORTE_EVIDENCIA_POC_LNB.md` — reporte con índices a toda la evidencia.
- `docs/evidencia/2026-09-15/README.md` — cómo leer el snapshot congelado.
- `DEMO.md` — runbook corto de la demo.