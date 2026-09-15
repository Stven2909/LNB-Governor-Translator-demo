# PLAN_POC_JAVA_V1
**Plan de implementación — Prueba Vertical CONTRACT_SYNTHETIC_V0 en Java**
**Estado:** Activo — esperando autorización de JD y accesos (runtime ya propuesto: Java 21 + Spring Boot 4.1.x)
**Fecha:** 07 septiembre 2026 (actualizado 14 septiembre 2026 — Anexo C)
**Confirmado por documento maestro de Alex:** Java 21 + Spring Boot (ver Anexo C)
**Responsables:** Carlos, Henry, Steven
**Base:** `CONTRACT_SYNTHETIC_V0.md` + `CATALOG_SYNTHETIC_V0.json` + `FIXTURE_SYNTHETIC_DEV.sql` + `PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md` + `Propuesta_Vertical_v2.pdf`

---

## Decisión de stack (cerrada para la PoC)

- **Lenguaje:** Java 21 LTS
- **Framework:** Spring Boot **4.1.x** (arrancar en 4.1.1) — compatible Java 17–26
- **Plataforma subyacente:** Spring Framework 7.x (Jakarta EE 11, Servlet 6.1, Jackson 3). Toda la generación Boot 4 (4.0 y 4.1) corre sobre Framework 7 — NO existe "4.0 con Framework 6".
- **Generación 3.x (Framework 6.x):** OSS EOL (3.4: 31-dic-2025; 3.5: 30-jun-2026). Solo colchón temporal, nunca punto de partida.
- **Build:** Maven 3.9+ (Gradle 8.5+ alternativo aceptable).
- **Starters mínimos:** `web`, `validation`, `actuator`, `test`. Nada de JPA/Cloud hasta Fase 6.
- **Driver Sybase (Fase 6):** jConnect (SAP), JDBC puro. Verificar contra Framework 7 (checkpoint abajo).

**Alineado con MASTER/Alex (Java 21 + Spring Boot) y `GOVERNOR_CONTRACT_V1.1:136`** (Java es la vía más directa para JDBC nativo). Evita migración Node→Java en Etapa B; un solo stack de punta a punta.

---

## Progreso (14 septiembre 2026)

**Fase 0 — 3 decisiones tomadas:** (1) JDK 21 + Boot 4.1.x listos y verificados localmente; (2) paquete `com.pagaduriasintetica.worker`, artefacto `worker-poc`; (3) PoC 100 % local con mocks (sin bloqueos externos). Pendiente solo la autorización formal de JD para continuar.

**Fase 1 — Entorno local COMPLETADA:**
- JDK **21.0.6 LTS** ✓ (Oracle, `C:\Program Files\Java\jdk-21`)
- Maven **3.9.16** instalado en `C:\Users\steve\tools\apache-maven-3.9.16` (MAVEN_HOME + PATH de usuario). Nota: winget no tiene Apache.Maven; se descargó el binario oficial de Apache dlcdn.
- Git **2.50.0** ✓; repo inicializado en `worker-poc/` (`.gitignore` del scaffold ya cubre target/, .idea/, .vscode/). Sin commit aún (pendiente OK).

**Fase 2 — Proyecto Spring Boot COMPLETADA:**
- Scaffold generado vía start.spring.io: **Boot 4.1.1**, Java 21, Maven, starters `webmvc, validation, actuator` (+ `*-test`), paquete `com.pagaduriasintetica.worker`.
- **Nota de versionado:** start.spring.io etiqueta la versión como `4.1.1.RELEASE`, pero Maven Central publica el artefacto como **`4.1.1`** (convención Boot 3+, sin sufijo). El pom queda con `4.1.1`; hay que ignorar el sufijo del UI de Initializr.
- Verificación: `mvn compile` EXIT=0; `mvn test` EXIT=0 (contexto arranca); `mvn package` EXIT=0; `java -jar` → Tomcat 11.0.24 en :8080, `GET /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}` HTTP 200.

**Siguiente:** Fase 3 (Worker core) — **COMPLETADA** (ver Progreso abajo).

**Fase 3 — Worker core COMPLETADA (14 septiembre 2026):**
- `POST /push` recibe envelope Pub/Sub (String raw) → decode Base64 → `200 ACK` tras commit (pasos 1–4 del contrato).
- Módulos implementados: `contract/` (DTOs+enums), `worker/` (PayloadHasher, StructuralValidator, StateStore+InMemoryStateStore=Sybase mock, WorkerService orquestación 14 pasos, PushController), `catalog/` (whitelist), `governor/` (MockGovernor inyectable para alucinación), `translator/` (plan de 8 params + preview_sql).
- Reserva atómica `INSERT ... PROCESSING` + matriz de PK violation (IDEMPOTENT / NACK en-vuelo / DLQ por colisión) + recuperación in-doubt (Rama1 promo, Rama2 reintento, Rama3 mismatch) — todos en `InMemoryStateStore` con semántica de putIfAbsent (PK simulada).
- **Corrección de datos encontrada y aplicada:** el `PAYLOAD_HASH` de ejemplo del contrato (`d340da60...`) NO coincide con el SHA-256 real de la cadena canónica. Verificado con Python y .NET → el hash correcto es **`8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6`**; actualizado en `CONTRACT_SYNTHETIC_V0.md` (§2.1 y §5) y bloqueado por `PayloadHasherTest`.
- Verificación: `mvn test` **16/16 verdes** (13 script + 2 hash + 1 context). Smoke HTTP real: POST envelope del contrato → 200 SUCCEEDED; réplica → 200 IDEMPOTENT (sin duplicar); `/actuator/health` UP.
- Notas de entorno: Jackson 3 usa `tools.jackson.*` (asString/propertyNames, elimina asText/fieldNames); PowerShell `Set-Content -Encoding utf8` agrega BOM (no usar para editar .java).

**Siguiente:** Fases 4–6 ya cubiertas con mocks en esta corrida; pendiente real: checkpoint jConnect (Fase 6) y Vertex real (Fase 8).

**Fases 4–5 — Cobertura de ramas COMPLETADA (14 septiembre 2026):**
- Nueva suite `BranchingCoverageTest` (JUnit + Mockito puro, sin ApplicationContext — `@MockBean`/`@SpyBean` fueron **removidos en Boot 4.0**; los tests de ramas no necesitan levantar contexto):
  - `caso3_prefiltroRechazaSinInvocarGobernadorNiTraductor`: Caso 3 verificado por comportamiento — `verify(governor, never()).decide(...)` y `verify(translator, never()).translate(...)`, no solo por estado final.
  - `caso9_gobernadorRechazaPorReglaDeNegocio`: rechazo del propio Gobernador (paso 9) → persiste `REJECTED` (NO `DLQ_QUARANTINED`), ACK, 0 pagos, Traductor nunca invocado. Distingue rechazo de negocio vs violación de contrato.
  - `caso_translatorDevuelveColumnaInventadaEsTranslationError`: columna fuera de `field_mapping` + técnicas → `TRANSLATION_ERROR`.
- `WorkerService`: fix de robustez `decision().name().equals("REJECTED")` → `decision() == GovernorDecision.REJECTED` (comparación de enum, amparada por el test nuevo). Guard defensivo post-Traductor con tipado a **`TRANSLATION_ERROR`** (columna inventada / tabla fuera de whitelist / params != 8) — rama distinta del contrato vs `DLQ_QUARANTINED` (Caso 4, sin cambios).
- `OperationStatus` += `TRANSLATION_ERROR` (17 chars, cabe en `VARCHAR(20)`); `status_classification` del catálogo actualizado (copias del recurso y de la raíz).
- Verificación: `mvn test` **19/19 verdes** (16 previos + 3 nuevos).

**Siguiente:** checkpoint jConnect (Fase 6) — el único paso técnico local restante; puede tocar el plan si Framework 7 choca con el driver.

**Fases 6–7 — Harness de demo y evidencia COMPLETADAS (15 septiembre 2026):**
- Refactor: `TestEnvelopeFactory` (fuente única de envelope/evento/hash) usado por `WorkerPipelineTest` y `BranchingCoverageTest`.
- `DemoEvidenceTest` (`@SpringBootTest`, pipeline real): harness con **16 escenarios** (matriz de los 6 casos + subcasos: 2a IDEMPOTENT, 2b en-vuelo→RETRYABLE, 2c PK collision→DLQ, 3 REJECTED pre-filtro, 3b op. no permitida, 4 alucinación→DLQ, 5 ramas 1/2/3, 6×3 malformados, paso 9 REJECTED del Gobernador, TRANSLATION_ERROR con translator mock aislado, hash canónico del contrato). `store.reset()`+`governor.resetOverride()` al inicio de cada escenario (el override del Caso 4 NO se filtra entre escenarios).
- `EvidenceWriter`: exporta `target/demo/` (gitignored y regenerable) — `evidencia_casos.json`, `EVIDENCIA_POC_LNB.md`, `envelopes/*.json` y `envelopes/live/*.json` (subconjunto POSTeable sin estado preparado).
- Demo en vivo: `scripts/demo-live-start/run/stop.ps1` (stop por listener de :8080). Corrida real: jar → POST 7 envelopes live-safe → **7/7 HTTP 200** con `SUCCEEDED / IDEMPOTENT / REJECTED×2 / DLQ_QUARANTINED×3`; resultado en `demo-live-run.json`.
- Verificación: `mvn test` **20/20 verdes** (19 previos + harness 16/16 PASS).
- Entregables: `REPORTE_EVIDENCIA_POC_LNB.md` (raíz), `worker-poc/DEMO.md`, snapshot congelado en `worker-poc/docs/evidencia/2026-09-15/`.
- Notas: el escenario "en vuelo" es simulado (PROCESSING pre-sembrado), no concurrencia real de hilos; jConnect/Vertex/Pub-Sub siguen siendo pendientes reales (Fases 6 y 8).

---

## Ruta de 9 fases

### Fase 0 — Pre-requisitos (decisiones)
1. Confirmar con JD: Java 21 + Spring Boot 4.1.x como runtime definitivo (Propuesta_v2, decisión 6).
2. Nombre de paquete/artefacto: `com.pagaduriasintetica.worker` (o `com.pagaduria.agentes`), artefacto `worker-poc`.
3. PoC 100 % local con mocks — no bloquea nada externo.

### Fase 1 — Entorno local
4. Verificar **JDK 21** (`java -version`).
5. Verificar **Maven 3.9+** (`mvn -version`).
6. Opcional: gcloud CLI + ADC (`gcloud auth application-default login`, proyecto `dulcet-listener-505916-n5`).
7. `git init` + `.gitignore` (target/, .idea/, .vscode/).
   **Entregable:** proyecto vacío que compila.

### Fase 2 — Proyecto Spring Boot
8. Scaffold con Spring Initializr: **Java 21, Maven, Boot 4.1.x**, starters `web`, `validation`, `actuator`, `test`.
9. Estructura de módulos internos (monolito, frontera lógica del contrato §2 — solo `jdbc/` toca driver real):
```
src/main/java/com/pagaduriasintetica/worker/
  worker/     <-- handler push, decode, validación, idempotencia, orquestación
  governor/   <-- pre-filtro catálogo + APPROVED/REJECTED (Vertex opcional)
  translator/ <-- sql_template + parameters (8)
  jdbc/       <-- interfaz + mock local (driver real jConnect después)
  contract/   <-- DTOs: evento, governor_contract, translator_result, estado
  catalog/    <-- carga CATALOG_SYNTHETIC_V0.json
```
10. Catálogo como recurso: `src/main/resources/catalog/CATALOG_SYNTHETIC_V0.json`.
11. `GET /` → `UP` (actuator/health).
    **Entregable:** `mvn spring-boot:run` arranca; `.gitignore` correcto.

### Fase 3 — Worker core (P0–P1)
12. `POST /push` recibe envelope Pub/Sub → decodifica `message.data` Base64 → `200 ACK`.
13. Validación estructural: `operationId/eventId/traceId/contract_version` + tipos de payload.
14. **`PAYLOAD_HASH` canónico**: llaves alfabéticas, `amount` a 2 decimales, SHA-256 hex (CONTRACT §2.1).
15. **Reserva atómica**: `INSERT ... PROCESSING` en `WORKER_OPERATION_STATE`; PK violation → matriz de estados (IDEMPOTENT / DLQ_QUARANTINED / In-Doubt) — nunca check-then-act.
    **Entregable:** Casos 1 y 2 verdes contra Sybase mock.

### Fase 4 — Gobernador + catálogo (P2–P3)
16. **Pre-filtro determinista**: `(entity, operation, eventType)` contra catálogo → `REJECTED` sin Vertex (Caso 3).
17. Gobernador con Vertex mock (aprobado/rechazado/alucinación inyectable) + **post-validación** del contrato contra whitelist → `DLQ_QUARANTINED` si no coincide (Caso 4).
    **Entregable:** Casos 3 y 4 verdes.

### Fase 5 — Traductor (P4)
18. `operation_data` + `governor_contract` + 3 técnicos + hash → `sql_template` (8 params) + `parameters` + `preview_sql`.
19. Regla: tabla/columnas SOLO del catálogo; columna extra → `TRANSLATION_ERROR`.
    **Entregable:** Caso 1 genera el plan exacto (8 valores).

### Fase 6 — JDBC adapter (P5) + CHECKPOINT jConnect
20. Interfaz `SybaseWriter` + **mock local**; transacción `INSERT SYNTHETIC_PAYMENTS` + `UPDATE estado` en la misma transacción (sin 2PC).
21. **In-Doubt recovery** (Caso 5): estado `PROCESSING/RETRYABLE` → consultar `SYNTHETIC_PAYMENTS` por `OPERATION_ID` y comparar `PAYLOAD_HASH` → `SUCCEEDED` sin reinsertar.
22. Caso 6 (mensaje inválido/Base64 corrupto) → `DLQ_QUARANTINED`.
23bis. **CHECKPOINT jConnect vs Framework 7:** probar driver real contra Boot 4.1.x (Jakarta EE 11, Servlet 6.1).
   - Riesgo esperado BAJO: jConnect habla TDS directo, no usa clases de Spring Web.
   - Si el checkpoint encuentra un fallo **concreto** (no solo falta de certificación formal): única alternativa real = bajar a **3.5.x** (Framework 6.x, OSS EOL pero con historial de compatibilidad del driver) como colchón temporal.
   - NO existe "4.0 con Framework 6" (4.0 también es Framework 7, con ventana de soporte menor: EOL dic-2026).
    **Entregable:** Casos 5 y 6 verdes; decisión jConnect documentada.

### Fase 7 — Suite E2E local (P6)
23. Harness de **los 6 casos** con Pub/Sub mock + Sybase mock + Vertex mock → corrida determinista y evidencia exportable (logs con `operationId/eventId/messageId/traceId`).

### Fase 8 — GCP real (cuando Cloudfly entregue accesos)
24. Topic + suscripción push → endpoint Cloud Run; IAM (`roles/iam.serviceAccountTokenCreator` al agente de Pub/Sub — gotcha del laboratorio).
25. Desplegar Cloud Run (buildpack Java, sin Docker). Aplicar `FIXTURE_SYNTHETIC_DEV.sql` en Sybase DEV.
26. E2E real: 6 casos contra infra real, evidencia de commit en `SYNTHETIC_PAYMENTS`.

### Fase 9 — Reporte a JD
27. Entregable final: evidencia de 6 casos, bloqueos reales encontrados (Propuesta_v2), punto de reemplazo del catálogo sintético → real (`CATALOG_REAL_V1`).

---

## Pendientes que no bloquean Fases 1–7
- Accesos Pub/Sub + Sybase DEV (Cloudfly/LNB) — solo Fase 8.
- Decisión formal de JD sobre runtime — propuesta sobre la mesa (Java 21 + Boot 4.1.x).
- Contrato real de Alex (`CONTRACT_REAL_V1`) — futuro, post-PoC.

---

## Anexo C — Hallazgos vs Documento Maestro de Alex (LNB)

**Fuente:** `LNB_Documento_Maestro_API_Priorizacion_Servicios_Pagaduria (1).pdf` (64 págs., V1.1, "GRUPO AVANZA")
**Fecha de análisis:** 14 septiembre 2026

### 1. Confirmación del stack (punto de cierre de la decisión)
- **Pág. 3 (MTS):** *"API-first, Java 21, Spring Boot, Cloud Run y frontera legacy."*
- **Pág. 56 (I2/CÓDIGO):** *"Estructura Java 21 + Spring Boot"* — capas `api / application / domain / infrastructure.{persistence,legacy,gcp} / security / observability`; **recomendación: modular monolith desplegable** (igual que nuestra PoC); capa `domain` **sin Spring**.
- **No fija versión de Spring Boot, ni Maven/Gradle/Jakarta/JDBC.** Nuestra elección Boot 4.1.x es independiente y **no contradicha**.

### 2. Deltas que afectan la PoC (llevar a JD/Carlos)
1. **Transporte API→Worker — contradicción entre documentos oficiales:**
   - Maestro (Alex), pág. 48: Worker lee outbox con `SKIP LOCKED`, estados `PENDING/PROCESSING/SUCCEEDED/RETRYABLE/DEAD_LETTER/QUARANTINED`. **No menciona Pub/Sub en absoluto.**
   - Guía de transición LNB (RESUMEN): `Outbox → Pub/Sub → Worker`.
   - **Acción Fase 8:** confirmar si el Worker escucha push de Pub/Sub o realiza outbox polling antes de crear topic/suscripción.
2. **Reclamos/pagos NO marcados pausados** en el maestro: todo bloque A ("Primero"): Prize Claims, Validation, Classification & Calculation, Payment & Disbursement, Identity, Catalogos. Sin *blocked/paused* en el documento. Confirmar con Carlos si la pausa mencionada en `RESUMEN:5` es posterior al doc o fue levantada.
3. **Volumetría oficial: 90 tablas / 15 esquemas** (pág. 3) — no "50 tablas/Bloke A" del resumen.
4. **Convenciones a alinear (CATALOG_REAL futuro):**
   - `amount` **string decimal, nunca float binario** (pág. 10) → la canonicalización deberá contemplar decimales como string.
   - `Idempotency-Key` obligatoria en POST que crea/confirma dinero; misma llave + body distinto → **HTTP 409** (pág. 17). Mapea directo a nuestro `operationId + PAYLOAD_HASH` (mismatch → conflicto → DLQ_QUARANTINED).
   - `X-Correlation-Id` → nuestro `traceId`; estados de reclamo `DRAFT→…→PAID→SYNC_PENDING→SYNCED` + laterales `CANCELLED/REJECTED/MANUAL_REVIEW/SYNC_FAILED` (pág. 19); pago `DRAFT/AUTHORIZED/COMMITTED/MANDATE_READY/DISBURSED/SYNCED` (pág. 20). Coherente con nuestra matriz de estados de integración.
   - Códigos de error estables (pág. 13): incluyen **504 LEGACY_TIMEOUT** (worker), **503 DEPENDENCY_UNAVAILABLE** (Sybase no disponible) — compatibles con nuestro slot del Gobernador.
   - Tablas confirmadas como prioridad A: `prize.prize_claim`, `prize.payment`, `finance.withholding_rule`, `integration.outbox_event`. "Las tablas no se exponen como CRUD genérico" (pág. 46).

### 3. Ausencias relevantes
- **No existe "Governor/Translator/IA/LLM"** en el maestro (0 menciones). El único "translator" es el adaptador de protocolo Sybase (pág. 56). La estrategia de agentes IA de Pagaduría Digital es **nuestra implementación**, no un requisito del documento.
- **Pub/Sub: 0 menciones** (ver delta 1). La asincronía canónica del maestro es outbox + worker con replica a Sybase *post-commit* y estado de sincronización separado.

## Referencias
- CONTRACT_SYNTHETIC_V0.md — contrato, canonicalización, reserva atómica, transacción
- CATALOG_SYNTHETIC_V0.json — whitelist y reglas
- FIXTURE_SYNTHETIC_DEV.sql — DDL fixture + WORKER_OPERATION_STATE
- PRUEBA_VERTICAL_CASOS_SYNTHETIC_V0.md — 6 casos y pasos del Worker
- GOVERNOR_CONTRACT_V1.1-2.docx — monolito, IDs, idempotencia, catálogo externo
- Propuesta_Vertical_v2.pdf — alcance, 14 pasos, 8 decisiones, criterio de salida