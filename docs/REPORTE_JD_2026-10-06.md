# Reporte de Estado — Equipo Agentes & Vertex
**Fecha:** 6 de octubre de 2026
**Proyecto:** Pagaduría Digital — prueba vertical LNB
**Contrato:** CONTRACT_PAYMENT_COMMITTED_V0.1

---

## 1. Tareas estructuradas por responsable

| Responsable | Frente | Estado actual | Pendiente | Criterio de cierre | Fecha entrega |
|---|---|---|---|---|---|
| **Carlos** (E3.1) | Gobernador real (Vertex AI), prompt, contrato con Alex | Scaffold listo (`MockGovernor`, `GovernorContract`, `Catalog.validateGovernor()`) | SDK `com.google.genai:google-genai` en `pom.xml`, prompt estructurado, conectar validación anti-alucinación a respuestas reales | 16 escenarios con Vertex real en DEV + prompt versionado + validación anti-alucinación conectada | 16/10 (review técnica) |
| **Steven** (E3.2–E3.5) | Recorrido del worker, ACK/NACK, errores/reintentos, reporte a la API, publicador Outbox→Pub/Sub | 75/75 tests, 16/16 escenarios, `PushController` + `WorkerService` + estados | SDK Vertex (depende de Carlos), reporte HTTP real, publicador Outbox, validación de eventos (COMMITTED/whitelist), colisión hash post-commit | 16 payloads vía Pub/Sub real + reporte HTTP con contrato 409 cerrado + publicador Outbox operativo | 19/10 (ensayo integral) |
| **Henry** | Arquitectura, monitoreo, seguimiento de entrega | Health checks configurados, plantillas de despliegue | Evidencia de monitoreo en DEV (logs Cloud Logging), dashboard de estado | Dashboard de estado con métricas de worker + alertas configuradas + logs de DEV visibles | 14/10 |
| **JD/LNB** | Infraestructura: Pub/Sub, contenedores, VPN, accesos | Accesos habilitados en `proy-comercial-dev-lnb` | Suscripción push autenticada mediante OIDC e IAM, VPN Cloud Run→Sybase, credenciales Secret Manager | Suscripción push operativa + VPN verificada + secretos accesibles por la SA de ejecución | 14-15/10 |
| **Alex** | PostgreSQL/Sybase, driver jConnect, DDL, credenciales | VM `poc-connect-sybase` verificada (conectividad TCP a `192.168.2.14:5000`) | Driver jConnect (procedencia/licencia), credenciales BD, tablas Sybase DEV | Driver recibido + credenciales operativas + tablas Sybase creadas | Pendiente confirmación |
| **Outbox Publisher** | Publicador Outbox → Pub/Sub | No existe | **Responsable por confirmar con JD/API; propuesta: Steven** | Responsable confirmado + publicador operativo + eventos publicados desde PostgreSQL | Pendiente confirmación |

---

## 2. Estado real con evidencia

### Lo que YA funciona (verificado hoy — 6 de octubre de 2026)

| Componente | Evidencia | Resultado |
|---|---|---|
| **Suite de pruebas** | `mvn -B test` ejecutado 6/10, commit `43cc5ee` — salida adjunta en `docs/evidencia/2026-10-06/mvn-test-output.txt` | **75/75 tests en verde** (10 clases) |
| **Harness local del pipeline** | `DemoEvidenceTest` — 16 escenarios con mocks/fixtures | **16/16 PASS** |
| **Hash canónico** | SHA-256 del payload de referencia | `0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8` — **CORREGIDO** (tenía 66 chars, ahora 64) |
| **Imagen Docker** | `docker build -t worker-poc:dev .` | Build exitoso — tamaño pendiente de verificar con `docker image inspect` |
| **Health en contenedor** | `docker run` + `curl /actuator/health/readiness` | `{"status":"UP"}` |
| **Usuario no-root** | `docker exec worker-test id` | `uid=1001(lnb)` |
| **Binding de variables** | `APP_MAX_UNKNOWN_RETRIES=99` → falla arranque por `@Max(20)` | Confirmado |
| **Demo HTTP local** | 7 POSTs a `/push` | 7/7 HTTP 200 — **HTTP 200 demuestra ACK, no necesariamente pago exitoso** |

### Evidencia de ejecución (6 de octubre de 2026)

```
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- BranchingCoverageTest
Tests run: 12, Failures: 0, Errors: 0, Skip: 0 -- CommitMeansSuccessTest
Tests run: 7,  Failures: 0, Errors: 0, Skip: 0 -- ConcurrentRedeliveryTest
Tests run: 1,  Failures: 0, Errors: 0, Skip: 0 -- DemoEvidenceTest
Tests run: 8,  Failures: 0, Errors: 0, Skip: 0 -- GovernorFailureClassificationTest
Tests run: 4,  Failures: 0, Errors: 0, Skip: 0 -- OperationStateMachineTest
Tests run: 3,  Failures: 0, Errors: 0, Skip: 0 -- PayloadHasherTest
Tests run: 24, Failures: 0, Errors: 0, Skip: 0 -- WorkerPipelineTest
Tests run: 1,  Failures: 0, Errors: 0, Skip: 0 -- WorkerPocApplicationTests

TOTAL: Tests run: 75, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

> **Nota:** La evidencia del harness congelado (`docs/evidencia/2026-09-22/`) corresponde a una corrida del 30/9. La corrida del 6/10 (arriba) confirma que los 75 tests siguen en verde con el código actual.

### Monitoreo (estado actual)

| Componente | Estado | Evidencia |
|---|---|---|
| Health checks | ✅ Configurados | `/actuator/health/readiness` → `{"status":"UP"}` |
| Logs en Cloud Logging | ⏳ Pendiente | No hay servicio desplegado en DEV aún — no hay logs que revisar |
| Dashboard de estado | ⏳ Pendiente | Henry: pendiente de configuración |
| Alertas | ⏳ Pendiente | Henry: pendiente de configuración |

> **Nota honesta:** El monitoreo en DEV no tiene evidencia real todavía porque el worker no está desplegado en Cloud Run. Lo que existe es la configuración de health checks y la capacidad de Cloud Logging, pero no hay logs ni métricas de una corrida real en DEV.

### Desglose de las 75 pruebas (detalle por clase — 10 clases)

#### 2.1 `WorkerPipelineTest` — 24 tests
**Cobertura:** Suite E2E con contexto Spring real (`@SpringBootTest`). Pipeline completo desde la recepción del mensaje hasta la persistencia en Sybase y el reporte. Incluye los 6 casos de la prueba vertical LNB, subcasos de in-doubt, y las pruebas del plan Fase 5.

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `caso1_nuevoValidoLlegaASucceeded` | Pipeline completo: evento válido → SUCCEEDED con pago persistido, hash correcto, exactamente 1 ejecución JDBC | PROCESSING → JDBC_COMMITTED → REPORT_PENDING → SUCCEEDED | — |
| 2 | `caso2a_duplicadoMismoHashEsIdempotent` | Reenvío idéntico (mismo hash canónico) → IDEMPOTENT, sin duplicar fila ni re-ejecutar JDBC | SUCCEEDED → IDEMPOTENT | — |
| 3 | `caso2b_eventoEnVueloRespuestaNack` | Evento ya en PROCESSING (sin fila persistida) → NACK RETRYABLE para redelivery programada | PROCESSING → RETRYABLE | Operación en vuelo |
| 4 | `caso2c_operationIdRecicladoConHashDiferenteEsDlq` | Mismo operationId con payload distinto → colisión de PK → ACK DLQ. El estado SUCCEEDED terminal NO se degrada | SUCCEEDED → DLQ_QUARANTINED | Colisión de PK |
| 5 | `caso3_aggregateTypeFueraDelCatalogoEsRejected` | aggregateType fuera de whitelist → REJECTED por pre-filtro, sin reservar fila | → REJECTED (sin estado previo) | Pre-filtro del catálogo |
| 6 | `caso3b_eventTypeFueraDelCatalogoEsRejected` | eventType != PAYMENT_COMMITTED → REJECTED por pre-filtro | → REJECTED | Pre-filtro del catálogo |
| 7 | `caso4_alucinacionDelGobernadorEsDlq` | Gobernador devuelve tabla inventada → DLQ con razón "Governor output violated catalog whitelist" | → DLQ_QUARANTINED | Alucinación del LLM |
| 8 | `caso5_rama1_inDoubtPromueveASucceededSinReinsertar` | In-doubt con hash idéntico → promueve a SUCCEEDED sin reinsertar ni re-ejecutar JDBC | IN_DOUBT → SUCCEEDED | Recuperación in-doubt |
| 9 | `caso5_rama3_paymentHashMismatchEsDlq` | In-doubt con PAYLOAD_HASH distinto → DLQ por inconsistencia de BD | IN_DOUBT → DLQ_QUARANTINED | Inconsistencia de datos |
| 10 | `caso5_rama2_retryableSinCommitReintentaFlujoCompleto` | Operación en RETRYABLE sin commit → redelivery reprocesa completo → SUCCEEDED | RETRYABLE → PROCESSING → SUCCEEDED | — |
| 11 | `caso6_base64CorruptoEsDlq` | Base64 inválido → DLQ en el decode | → DLQ_QUARANTINED | Corrupción de encoding |
| 12 | `caso6_jsonMalformadoEsDlq` | Base64 válido pero JSON malformado → DLQ al parsear | → DLQ_QUARANTINED | JSON inválido |
| 13 | `caso6_faltanOperationIdYPayloadEsDlq` | Faltan operationId y operationData → DLQ estructural | → DLQ_QUARANTINED | Schema incompleto |
| 14 | `eventVersionInvalidaEsDlqEstructural` | eventVersion=2 (inválida) → DLQ estructural | → DLQ_QUARANTINED | Versión incorrecta |
| 15 | `destinationSystemDistintoDeSybaseEsDlq` | destinationSystem="ORACLE" → DLQ estructural | → DLQ_QUARANTINED | Ruteo equivocado |
| 16 | `campoObligatorioAusenteEnOperationDataEsDlq` | paymentId=null → DLQ estructural | → DLQ_QUARANTINED | Campo obligatorio ausente |
| 17 | `montosNegativosEsDlq` | Montos negativos → DLQ estructural | → DLQ_QUARANTINED | Validación de montos |
| 18 | `invarianteMonetariaVioladaEsRejected` | netAmount != gross - withholding → REJECTED (validationRules) | → REJECTED | Invariante monetaria |
| 19 | `duplicadoEnJdbcCommittedSoloReintentaReporte` | Duplicado en JDBC_COMMITTED → reintenta SOLO el reporte, sin re-ejecutar JDBC | JDBC_COMMITTED → SUCCEEDED | — |
| 20 | `jdbcUnknowngeneraInDoubtYSeConcilia` | JDBC UNKNOWN → IN_DOUBT + NACK; redelivery concilia a SUCCEEDED sin re-ejecutar JDBC | PROCESSING → IN_DOUBT → SUCCEEDED | JDBC UNKNOWN |
| 21 | `falloTemporalDelReporteRecuperaSinReejecutarJdbc` | Fallo del reporte → REPORT_PENDING (no RETRYABLE); redelivery reintenta solo reporte → SUCCEEDED | JDBC_COMMITTED → REPORT_PENDING → SUCCEEDED | Fallo temporal del endpoint |
| 22 | `reporteConflict409NoReejecutaJdbc` | 409 CONFLICT del endpoint → promueve a SUCCEEDED sin re-ejecutar JDBC | REPORT_PENDING → SUCCEEDED | Conflicto 409 |
| 23 | `redeliveryTrasSucceededNoReejecutaJdbc` | Redelivery tras SUCCEEDED → IDEMPOTENT, sin segunda ejecución JDBC | SUCCEEDED → IDEMPOTENT | — |
| 24 | `traductorRealEmiteDoceParametros` | El Traductor real emite exactamente 12 parámetros (8 negocio + 4 trazabilidad) | → SUCCEEDED | — |

**Helpers:** `TestEnvelopeFactory`, `InMemoryStateStore` (reset por test), `MockGovernor` (override alucinación), `MockResultReporter` (override fallos/409), `FixtureJdbcExecutor` (outcome forzable), `jdbcBaseline`/`jdbcDelta()` para medir ejecuciones JDBC.

---

#### 2.2 `CommitMeansSuccessTest` — 12 tests
**Cobertura:** Frontera del Bloque 1 (revisión de Carlos). Principio "commit significa éxito": un fallo del reporte con Sybase ya confirmado NO puede cuarentenar la operación. Límites de reintento, distinción cuarentena funcional vs DLQ nativa, prohibición de cuarentena técnica tras el commit. Suite sin `@SpringBootTest` (mocks).

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `falloDelReporteConservaElCommitYNoQuarantina` | Fallo del reporte con commit confirmado → REPORT_PENDING, sin cuarentena, sin degradar a RETRYABLE | JDBC_COMMITTED → REPORT_PENDING | Fallo del endpoint de reporte |
| 2 | `redeliveryTrasFalloDeReporteReintentaSoloElReporte` | Redelivery tras fallo de reporte → reintenta SOLO el reporte, sin re-ejecutar JDBC | REPORT_PENDING → SUCCEEDED | Recuperación tras fallo |
| 3 | `reintentosDeReporteAgotadosBloqueanElReporteSinQuarantinar` | Al agotar reintentos del reporte → ReportStatus.BLOCKED + manualActionRequired, sin cuarentena técnica | REPORT_PENDING (bloqueado) | Reintentos agotados |
| 4 | `errorDesconocidoTrasCommitBloqueaElReporteYPreservaElPago` | Excepción no prevista tras commit → reporte bloqueado, pago preservado, sin cuarentena | REPORT_PENDING (bloqueado) | Bug en cliente de reporte |
| 5 | `errorDesconocidoAntesDelCommitAgotaIntentosYVaACuarentenaTecnica` | Error desconocido ANTES del commit → reintento limitado (3 intentos) → cuarentena TÉCNICA | PROCESSING → RETRYABLE → QUARANTINE_TECHNICAL | Bug antes del commit |
| 6 | `errorDesconocidoAntesDelCommitReintentaMientrasQuedenIntentos` | Error desconocido antes del commit con intentos disponibles → RETRYABLE + NACK | PROCESSING → RETRYABLE | Reintento transitorio |
| 7 | `laMaquinaDeEstadosProhibeCuarentenaTrasElCommit` | La máquina PROHÍBE transiciones a QUARANTINE_TECHNICAL o BLOCKED_CONFIGURATION desde JDBC_COMMITTED o REPORT_PENDING | — (test de máquina) | Transiciones ilegales |
| 8 | `marcarReporteBloqueadoExigeQueElCommitEsteConfirmado` | No se puede marcar reporte como bloqueado si no hubo commit (estado PROCESSING) | — (test de invariante) | Operación sin commit |
| 9 | `siFallaElRegistroDelEstadoNoSeRespondeConAck` | Si falla el registro del estado → se propaga excepción (NO se puede responder 200/ACK) | — (test de propagación) | Fallo de BD al persistir |
| 10 | `laInvarianteMonetariaEsRechazoDeNegocioNoCuarentena` | Invariante monetaria violada → REJECTED (no es cuarentena) | → REJECTED | Rechazo de negocio |
| 11 | `laCuarentinaFuncionalDelGovernorQuedaTipada` | GovernorInvalidResponseException → DLQ_QUARANTINED con QuarantineType.FUNCTIONAL | → DLQ_QUARANTINED (funcional) | Respuesta fuera de whitelist |
| 12 | `timeoutDelGovernorAntesDelJdbcNoDejaPagoNiReservaAtascada` | Timeout del Governor → RETRYABLE + NACK, sin JDBC, sin estado terminal | PROCESSING → RETRYABLE | Timeout de Vertex |

**Helpers:** `BranchingCoverageTestSupport`, `Mockito.mock(Governor/Translator)`, `InMemoryStateStore`, `FixtureJdbcExecutor`, `MockResultReporter`, constantes `MAX_UNKNOWN_RETRIES=3`/`MAX_REPORT_ATTEMPTS=5`, `handle(operationId, deliveryAttempt)`.

---

#### 2.3 `GovernorFailureClassificationTest` — 8 tests
**Cobertura:** Clasificación de fallos del Governor (G4, revisión de Carlos). Cada tipo de excepción se clasifica correctamente: timeout/5xx → RETRYABLE, respuesta inválida → cuarentena funcional, config/permisos → BLOCKED_CONFIGURATION, excepción no prevista → RETRYABLE. Suite sin `@SpringBootTest` (Mockito puro).

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `vertexNoDisponibleQuedaRetryableYConNack` | GovernorUnavailableException → RETRYABLE + NACK, sin JDBC, sin PROCESSING huérfano | PROCESSING → RETRYABLE | Timeout/5xx de Vertex |
| 2 | `retryablePorVertexEsReclamableEnLaRedelivery` | Reserva en RETRYABLE es reclamable: redelivery vuelve a PROCESSING con bump de intento | RETRYABLE → PROCESSING → SUCCEEDED | Recuperación tras timeout |
| 3 | `respuestaInvalidaVaACuarentenaFuncionalConAck` | GovernorInvalidResponseException → DLQ_QUARANTINED + ACK, sin reintentos | → DLQ_QUARANTINED | Respuesta no parseable |
| 4 | `errorDeConfiguracionQuedaBlockedConfiguration` | GovernorConfigurationException → BLOCKED_CONFIGURATION + ACK, sin loop | → BLOCKED_CONFIGURATION | Credencial/permiso denegado |
| 5 | `redeliveryDeBlockedConfigurationConfirmaConAck` | Redelivery de BLOCKED_CONFIGURATION → ACK (es terminal, no se reabre) | BLOCKED_CONFIGURATION → BLOCKED_CONFIGURATION | Estado terminal |
| 6 | `excepcionNoPrevistaNoDejaReservaEnProcessing` | IllegalStateException del Governor → RETRYABLE, nunca PROCESSING huérfano | PROCESSING → RETRYABLE | Excepción no prevista |
| 7 | `excepcionNoPrevistaEnElTraductorTampocoDeJaProcessing` | Excepción no prevista en el Traductor → RETRYABLE, sin JDBC | PROCESSING → RETRYABLE | Bug en traductor |
| 8 | `elMotivoPersistidoDistingueClasificacionDeExcepcionNoPrevista` | El errorReason persistido contiene el detalle interno para diagnóstico | — (trazabilidad) | Diagnóstico operativo |

**Helpers:** `Mockito.mock(Governor/Translator)`, `BranchingCoverageTestSupport`, `InMemoryStateStore`, `FixtureJdbcExecutor`, `MockResultReporter`.

---

#### 2.4 `ConcurrentRedeliveryTest` — 7 tests
**Cobertura:** Idempotencia y exclusión bajo concurrencia (Bloque 2). Con N entregas simultáneas del mismo operationId, el JDBC se ejecuta una sola vez y la fila no se duplica. Mecanismo de lease con expiración para recuperar operaciones abandonadas. Suite con `@SpringBootTest`.

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `entregasSimultaneasDelMismoOperationIdEjecutanJdbcUnaSolaVez` | 16 hilos entregan el mismo operationId → JDBC se ejecuta 1 vez, fila no se duplica, intento=1 | → SUCCEEDED (concurrente) | Concurrencia de entregas |
| 2 | `soloUnHiloReclamaUnIntentoAbandonado` | N markedProcessing sobre mismo PROCESSING (lease vencido) → solo 1 gana, intento=2 | PROCESSING → PROCESSING (reclamo) | Actualización perdida |
| 3 | `unEstadoTerminalNoSeDegradaPorEscrituraConcurrente` | Escritura concurrente sobre estado SUCCEEDED → falla explícitamente, no degrada | SUCCEEDED (invariante) | Escritura atrasada |
| 4 | `conLeaseVigenteLaOperacionEnVueloNoSeRoba` | Con lease vigente → NACK RETRYABLE, no se roba el trabajo, intento no se infla | PROCESSING → RETRYABLE | Operación en vuelo |
| 5 | `elLeaseSeLiberaAlTerminarLaOperacion` | Al terminar la operación → lease se libera (leaseExpiry=null) | → SUCCEEDED (sin lease) | — |
| 6 | `conLeaseVencidoLaOperacionSeReclamaYSeReintenta` | Lease vencido (MutableClock) → se reclama y ejecuta flujo completo en mismo intento | PROCESSING → SUCCEEDED | Recuperación tras crash |
| 7 | `operacionesDistintasEnParaleloNoSeInterfieren` | 12 operaciones independientes en paralelo → cada una ejecuta su JDBC, sin interferencia | → SUCCEEDED (12 veces) | Aislamiento entre operaciones |

**Helpers:** `MutableClock` (reloj mutable para expiración de lease), `runInParallel`/`inParallel` (CountDownLatch), `reserveProcessing`, `jdbcBaseline`/`jdbcDelta()`.

---

#### 2.5 `WorkerPropertiesTest` — 11 tests
**Cobertura:** Validación de configuración de despliegue (`WorkerProperties`). Configuración tipada, validada al arrancar (fail-fast), libre de secretos, valores por defecto que permitan perfil mock ejecutable. Tests puros de unidad.

| # | Método | Qué verifica | Escenario de error/borde |
|---|---|---|---|
| 1 | `losValoresPorDefectoDejanElPerfilMockEjecutable` | Defaults: governor=MOCK, store=MEMORY, reporter=MOCK, ingest=PUSH, region=us-central1, timeout=30s | — |
| 2 | `vertexSinProjectIdFallaAlArrancar` | Vertex sin project-id → IllegalStateException "app.vertex.project-id" | Fail-fast en arranque |
| 3 | `reporteHttpSinBaseUrlFallaAlArrancar` | Reporter HTTP sin base-url → IllegalStateException "app.report.base-url" | Fail-fast en arranque |
| 4 | `jdbcSinUrlFallaAlArrancar` | Store JDBC sin URL → IllegalStateException | Fail-fast en arranque |
| 5 | `pullSinSuscripcionFallaAlArrancar` | Ingest PULL sin subscriptionId → IllegalStateException | Fail-fast en arranque |
| 6 | `laClaveDeIdempotenciaEsEstableYSeparadaDelIntento` | Header idempotencia = "Idempotency-Key", token env var = "REPORT_API_TOKEN" | — |
| 7 | `elRetryDeJdbcDesconocidoEstaProhibidoPorDefecto` | retryOnUnknown=false por defecto (UNKNOWN → IN_DOUBT, nunca re-ejecutar) | — |
| 8 | `noSeAlmacenanSecretosSoloReferencias` | toString() no expone usuario de BD, solo flags "configured=true" | Exposición de secretos |
| 9 | `losTimeoutsSonDuracionesTipadas` | Timeouts son Duration (no cadenas): report=3s, jdbc=15s, pubsub ackDeadline=60s | Tipos fuertes |
| 10 | `laPropiedadEstaAnotadaParaBindingYValidacion` | @ConfigurationProperties(prefix="app") + @Validated presentes | — |
| 11 | `losSubObjetosNoSonNulosEnElBindingPorDefecto` | Sub-objetos (vertex, jdbc, pubsub) tienen valores no nulos tras binding | — |

**Helpers:** Sin fixtures externos. Usa `new WorkerProperties()` directamente.

---

#### 2.6 `BranchingCoverageTest` — 4 tests
**Cobertura:** Ramas de decisión de LNB verificadas por comportamiento (Mockito puro, sin ApplicationContext). Pre-filtro del catálogo, invariante monetaria, rechazo del Gobernador por regla de negocio, TRANSLATION_ERROR.

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `caso3_prefiltroRechazaSinInvocarGobernadorNiTraductorNiReservar` | aggregateType fuera de catálogo → REJECTED, sin invocar Governor/Traductor, sin reservar | → REJECTED (sin fila) | Pre-filtro del catálogo |
| 2 | `invarianteMonetariaVioladaRejectedAntesDelGobernador` | Invariante monetaria violada → REJECTED, sin invocar Governor (regla determinista) | → REJECTED | Validación de negocio |
| 3 | `caso9_gobernadorRechazaPorReglaDeNegocio` | Governor rechaza por regla de negocio → REJECTED (no es violación de contrato) | → REJECTED | Rechazo de negocio |
| 4 | `caso_translatorDevuelveColumnaInventadaEsTranslationError` | Traductor devuelve columna inventada → TRANSLATION_ERROR, sin JDBC | → TRANSLATION_ERROR | Columna fuera de whitelist |

**Helpers:** `whenGovernorRejects()`, `whenGovernorApproves(operationId)`, `Mockito.mock(Governor/Translator)`, `InMemoryStateStore`, `FixtureJdbcExecutor`, `MockResultReporter`.

---

#### 2.7 `OperationStateMachineTest` — 4 tests
**Cobertura:** Política de transiciones de la máquina de estados. Transiciones válidas permitidas, saltos inválidos rechazados. Tests puros de unidad.

| # | Método | Qué verifica | Escenario de error/borde |
|---|---|---|---|
| 1 | `permiteTransicionesValidasDeProcessing` | PROCESSING → JDBC_COMMITTED, REJECTED, RETRYABLE, IN_DOUBT son válidas | Transiciones válidas |
| 2 | `permiteRedeliveryDesdeJdbcCommittedHaciaReporte` | JDBC_COMMITTED → REPORT_PENDING, REPORT_PENDING → RETRYABLE/SUCCEEDED, IN_DOUBT → SUCCEEDED son válidas | Transiciones válidas |
| 3 | `rechazaTransicionDesdeTerminal` | SUCCEEDED → PROCESSING, REJECTED → PROCESSING, DLQ_QUARANTINED → SUCCEEDED lanzan IllegalStateException | Transiciones ilegales |
| 4 | `rechazaSaltoNoPermitidoEnProcesamiento` | PROCESSING → SUCCEEDED lanza IllegalStateException (salto no permitido) | Transición ilegal |

**Helpers:** Sin fixtures. Usa `new OperationStateMachine()` directamente.

---

#### 2.8 `PayloadHasherTest` — 3 tests
**Cobertura:** Hash canónico del payload del contrato. Hash coincide con el ejemplo del contrato, montos normalizados a 2 decimales, equivalencia decimal. Tests puros de unidad.

| # | Método | Qué verifica | Escenario de error/borde |
|---|---|---|---|
| 1 | `canonicalHashMatchesContractExample` | Hash del fixture válido coincide con HASH_OP001 del contrato | — |
| 2 | `amountIsNormalizedToTwoDecimals` | Montos enteros (200, 50, 150) producen el mismo hash que con decimales (200.00, 50.00, 150.00) | Normalización de montos |
| 3 | `decimalEquivalence` | BigDecimal("1.10") y BigDecimal("1.1") producen el mismo hash | Equivalencia decimal |

**Helpers:** `TestEnvelopeFactory.HASH_OP001`, `TestEnvelopeFactory.validData()`, `new PayloadHasher()`.

---

#### 2.9 `DemoEvidenceTest` — 1 test (orquestador de 16 escenarios)
**Cobertura:** Harness local del pipeline (con mocks/fixtures). Corre la matriz completa de 16 escenarios de la prueba vertical LNB contra el pipeline real (contexto Spring) y exporta la evidencia a `target/demo/` vía `EvidenceWriter`. Cada escenario registra un "expediente" (Map).

| # | Método | Qué verifica | Estados | Escenario de error/borde |
|---|---|---|---|---|
| 1 | `matrizCompletaDeEvidencia` | Ejecuta los 16 escenarios y verifica que todos pasen (PASS). Es el único @Test; los demás son métodos privados invocados desde aquí. | — (orquestador) | — |

**Escenarios privados invocados desde `matrizCompletaDeEvidencia`:**

| # | Método | Escenario | Estado esperado |
|---|---|---|---|
| 1 | `caso1_nuevoValido` | Pipeline completo → SUCCEEDED | SUCCEEDED |
| 2 | `caso2a_duplicadoIdempotente` | Reenvío idéntico → IDEMPOTENT | IDEMPOTENT |
| 3 | `caso2b_inFlightRetryable` | Evento en vuelo → RETRYABLE (NACK) | RETRYABLE |
| 4 | `caso2c_pkCollisionDlq` | Mismo operationId con otra carga → DLQ | DLQ_QUARANTINED |
| 5 | `caso3_prefiltroRejected` | aggregateType no autorizado → REJECTED | REJECTED |
| 6 | `caso3b_eventTypeNoPermitido` | eventType fuera de catálogo → REJECTED | REJECTED |
| 7 | `caso4_governorAlucinaDlq` | Gobernador inventa tabla → DLQ | DLQ_QUARANTINED |
| 8 | `caso5r1_inDoubtRecuperado` | In-doubt con hash igual → SUCCEEDED | SUCCEEDED |
| 9 | `caso5r2_retryReprocesado` | RETRYABLE reprocesado → SUCCEEDED | SUCCEEDED |
| 10 | `caso5r3_inDoubtHashDistintoDlq` | In-doubt con hash distinto → DLQ | DLQ_QUARANTINED |
| 11 | `caso6_base64CorruptoDlq` | Base64 inválido → DLQ | DLQ_QUARANTINED |
| 12 | `caso6_jsonMalformadoDlq` | JSON malformado → DLQ | DLQ_QUARANTINED |
| 13 | `caso6_faltanCamposDlq` | Faltan operationId/operationData → DLQ | DLQ_QUARANTINED |
| 14 | `paso9_governorRechazado` | Gobernador rechaza por regla de negocio → REJECTED | REJECTED |
| 15 | `translation_errorColumnaInventada` | Traductor inventa columna → TRANSLATION_ERROR | TRANSLATION_ERROR |
| 16 | `hasher_contrato` | Verificación pura del PAYLOAD_HASH canónico | PASS |

**Helpers:** `escenario(id, titulo, casoRef, esperado, ackEsperado, liveSafe, liveName)`, `finalizar(ev, outcome, esperado, ackEsperado)`, `fallo(ev, exception)`, `snapshot(ev, store, operationId)`, `estadoFila(OperationState)`, `evento(PaymentCommittedEvent)`, `plan(PaymentCommittedEvent)`, `valueRulesObject(cat, aggregateType)`, `EvidenceWriter.escribir(EVIDENCIAS)`, `EVIDENCIAS` (lista estática compartida), `TestEnvelopeFactory`, `MockGovernor` (override alucinación/rechazo), `MockResultReporter`, `FixtureJdbcExecutor`, `InMemoryStateStore`.

---

#### 2.10 `WorkerPocApplicationTests` — 1 test
**Cobertura:** Smoke de contexto — verifica que el grafo de beans completo del modular monolith arranca sin errores.

| # | Método | Qué verifica | Escenario de error/borde |
|---|---|---|---|
| 1 | `contextLoads()` | El contexto Spring completo (todos los beans: `PushController`, `WorkerService`, `MockGovernor`, `DeterministicTranslator`, `InMemoryStateStore`, `FixtureJdbcExecutor`, `MockResultReporter`, `OperationStateMachine`) arranca sin excepciones | Fallo de wiring o dependencia faltante |

**Helpers:** Sin fixtures. Usa `@SpringBootTest` directamente.

---

### Patrones comunes entre suites

| Patrón | Archivos que lo usan |
|---|---|
| `@SpringBootTest` (contexto real) | WorkerPipelineTest, ConcurrentRedeliveryTest, DemoEvidenceTest |
| Mockito puro (sin contexto) | CommitMeansSuccessTest, GovernorFailureClassificationTest, BranchingCoverageTest |
| Tests puros de unidad | WorkerPropertiesTest, OperationStateMachineTest, PayloadHasherTest |
| `FixtureJdbcExecutor` con `jdbcDelta()` | WorkerPipelineTest, ConcurrentRedeliveryTest |
| `MockGovernor` con override | WorkerPipelineTest, DemoEvidenceTest |
| `MockResultReporter` con override | WorkerPipelineTest, ConcurrentRedeliveryTest, DemoEvidenceTest |
| `BranchingCoverageTestSupport` (stubs) | CommitMeansSuccessTest, GovernorFailureClassificationTest |
| `TestEnvelopeFactory` | WorkerPipelineTest, BranchingCoverageTest, PayloadHasherTest, DemoEvidenceTest |
| `OperationStateMachine` directo | CommitMeansSuccessTest, GovernorFailureClassificationTest, BranchingCoverageTest, OperationStateMachineTest, ConcurrentRedeliveryTest, DemoEvidenceTest |

### Los 16 escenarios del harness

| Caso | Esperado | Obtenido | Resultado |
|---|---|---|---|
| caso1 | SUCCEEDED | SUCCEEDED | PASS |
| caso2a | IDEMPOTENT | IDEMPOTENT | PASS |
| caso2b | RETRYABLE | RETRYABLE | PASS |
| caso2c | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| caso3 | REJECTED | REJECTED | PASS |
| caso3b | REJECTED | REJECTED | PASS |
| caso4 | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| caso5-r1 | SUCCEEDED | SUCCEEDED | PASS |
| caso5-r2 | SUCCEEDED | SUCCEEDED | PASS |
| caso5-r3 | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| caso6-b64 | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| caso6-json | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| caso6-faltan | DLQ_QUARANTINED | DLQ_QUARANTINED | PASS |
| paso9 | REJECTED | REJECTED | PASS |
| translation | TRANSLATION_ERROR | TRANSLATION_ERROR | PASS |
| hasher | 0fd5240a… | 0fd5240a… | PASS |

### Lo que NO está probado aún (pendiente de integración real)

| Componente | Estado | Por qué no está probado |
|---|---|---|
| Vertex AI (Gobernador real) | Mock | Falta SDK `com.google.genai:google-genai` + prompt de Carlos |
| Pub/Sub real (push) | HTTP simulado | Falta suscripción push autenticada mediante OIDC e IAM (JD) |
| JDBC → Sybase | Fixture | Falta driver jConnect + VPN + credenciales |
| Reporte HTTP a la API | Mock | Falta endpoint real + contrato 409 |
| Almacenamiento durable | InMemory | Falta JDBC real (idempotencia entre instancias) |

---

## 3. Requisitos de contenedores, configuración y accesos

### Contenedor

| Ítem | Valor |
|---|---|
| Dockerfile | Multi-stage: build JDK 21.0.6_7 → runtime JRE 21.0.6_7 |
| Imagen base | `eclipse-temurin:21.0.6_7-jdk-jammy@sha256:42cafd54…` (digest fijado) |
| Usuario runtime | `uid=1001(lnb)` — no root |
| Puerto | `8080` (inyectado por Cloud Run vía `PORT`) |
| Health check | `/actuator/health/readiness` → `{"status":"UP"}` |
| Tamaño | 486 KB (imagen local) |

### Configuración (variables de entorno)

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto del servidor |
| `APP_GOVERNOR_MODE` | `MOCK` | `MOCK` o `VERTEX` |
| `APP_INGEST_MODE` | `PUSH` | `PUSH` o `PULL` |
| `APP_STORE_MODE` | `MEMORY` | `MEMORY` o `JDBC` |
| `APP_REPORTER_MODE` | `MOCK` | `MOCK` o `HTTP` |
| `APP_MAX_UNKNOWN_RETRIES` | `3` | Reintentos pre-commit |
| `APP_MAX_REPORT_ATTEMPTS` | `5` | Reintentos post-commit |
| `APP_VERTEX_PROJECT_ID` | — | Obligatorio si `GOVERNOR=VERTEX` |
| `APP_VERTEX_REGION` | `us-central1` | Región de Vertex AI |
| `APP_VERTEX_MODEL` | `gemini-2.5-flash` | Modelo LLM |
| `APP_REPORT_BASE_URL` | — | Obligatorio si `REPORTER=HTTP` |
| `APP_JDBC_URL` | — | Obligatorio si `STORE=JDBC` |

> **Nota:** En Cloud Run, el servicio usa la **cuenta asignada y credenciales automáticas (ADC)**. No se requiere `GOOGLE_APPLICATION_CREDENTIALS` en runtime. El acceso humano a metadata de Secret Manager no demuestra ni descarta que la cuenta de ejecución pueda leer un secreto — hay que verificar ambos permisos por separado.

### Accesos necesarios

| Recurso | Service account | Rol requerido | Estado |
|---|---|---|---|
| Cloud Run (despliegue) | `sa-build-agentes-dev-lnb@…` | Cloud Run Admin + Artifact Registry Writer | ✅ Habilitado |
| Cloud Run (ejecución) | `sa-run-agentes-dev-lnb@…` | Cloud Run Invoker + Pub/Sub Subscriber + Vertex AI User | ✅ Habilitado |
| Pub/Sub | `sa-run-agentes-dev-lnb@…` | Pub/Sub Publisher + Subscriber | ✅ Habilitado |
| Vertex AI | `sa-run-agentes-dev-lnb@…` | Vertex AI User | ✅ Habilitado |
| Secret Manager | `sa-run-agentes-dev-lnb@…` | Secret Manager Secret Accessor | ⏳ Pendiente — verificar que la SA de ejecución pueda leer el secreto (no solo metadata) |
| Cloud Run push (OIDC) | `--push-auth-service-account` (cuenta que autentica el push, puede ser distinta de la SA de ejecución) | `roles/run.invoker` sobre el servicio Cloud Run | ⏳ Pendiente — JD debe identificar la cuenta configurada en `--push-auth-service-account` y concederle `run.invoker`; además verificar que Pub/Sub pueda generar el token OIDC |

### Secretos (Secret Manager)

| Nombre del secreto | Versión | Variable de entorno (donde se inyectará) | Uso | Estado |
|---|---|---|---|---|
| `REPORT_API_TOKEN` (por confirmar) | `latest` (por confirmar) | `REPORT_API_TOKEN` | Token para el endpoint de reporte | ⏳ Pendiente — JD/Alex deben confirmar nombre del secreto, versión, y variable de inyección |
| `SYBASE_PASSWORD` (por confirmar) | `latest` (por confirmar) | `SYBASE_PASSWORD` | Contraseña de Sybase | ⏳ Pendiente — JD/Alex deben confirmar nombre del secreto, versión, y variable de inyección |

---

## 4. Fecha de integración PostgreSQL → Sybase

### Propuesta: **20 de octubre de 2026** (tentativa, sujeta a Go/No-Go)

| Hito | Fecha | Responsable | Criterio de éxito |
|---|---|---|---|
| Go/No-Go | 16/10 | Todo el equipo | Todos los gates verdes |
| Revisión técnica | 16/10 | Carlos + Steven | SDK Vertex + prompt listos |
| Ensayo integral | 19/10 | Todo el equipo | 16 escenarios con Vertex real |
| **Prueba completa** | **20/10** | Todo el equipo | Cadena completa PostgreSQL → Sybase |

### Dependencias que pueden bloquear la prueba

| # | Dependencia | Dueño | Estado | Riesgo |
|---|---|---|---|---|
| 1 | Driver jConnect (procedencia/licencia/checksum) | Alex | ❌ Pendiente | **ALTO** — sin driver no hay JDBC |
| 2 | Credenciales Secret Manager (valores) | Alex/JD | ⏳ Pendiente | **ALTO** — sin credenciales no hay conexión |
| 3 | Suscripción Pub/Sub push autenticada mediante OIDC e IAM | JD | ⏳ Pendiente | **MEDIO** — bloquea E3.3 pero no E3.4 |
| 4 | VPN Cloud Run → Sybase | JD | ⏳ Pendiente | **ALTO** — sin VPN no hay conectividad |
| 5 | SDK Vertex + prompt | Carlos | ⏳ Pendiente | **MEDIO** — bloquea E3.2 pero no E3.4 |
| 6 | Contrato 409 del reporte | Alex | ⏳ Pendiente | **BAJO** — no bloquea la prueba de Sybase |
| 7 | Publicador Outbox → Pub/Sub | **Por confirmar con JD/API; propuesta: Steven** | ⏳ Pendiente | **MEDIO** — bloquea la cadena completa |

### Criterio Go/No-Go (16 de octubre)

**Se mantiene el 20/10 si TODOS los siguientes gates están verdes:**
- [ ] Driver jConnect recibido y validado por Alex
- [ ] Credenciales Secret Manager operativas (valores, no solo metadata)
- [ ] VPN Cloud Run → Sybase verificada
- [ ] SDK Vertex (`com.google.genai:google-genai`) + prompt funcionales (Carlos)
- [ ] Suscripción Pub/Sub push autenticada mediante OIDC e IAM (JD)
- [ ] Publicador Outbox con responsable confirmado y publicación de eventos confirmados en PostgreSQL
- [ ] Worker desplegado en Cloud Run con push autenticado y políticas de reintentos/DLQ verificadas
- [ ] Gobernador real probado con modelo disponible (⚠️ `gemini-2.5-flash` retira 20/10 — confirmar modelo alternativo)
- [ ] JDBC, tablas autorizadas e idempotencia en Sybase
- [ ] Almacenamiento durable del worker, probado ante reinicio y múltiples instancias
- [ ] Endpoint de reporte operativo, autenticación y contrato acordados
- [ ] Evidencia del resultado final registrado por la API

**Se mueve al 27/10 (alternativa condicionada, no automática) si:**
- Cualquiera de los 3 primeros gates (driver, credenciales, VPN) no está listo, O
- El Go/No-Go del 16/10 detecta bloqueos en los demás gates

---

## 5. Gaps técnicos identificados (revisión de Carlos)

| # | Gap | Archivo | Criterio de aceptación |
|---|---|---|---|
| 1 | Validación de eventos incompleta: solo verifica presencia (blank), no valores | `StructuralValidator.java` | Rechazar `status != COMMITTED`, `payment_method` fuera de whitelist, `currency` fuera de whitelist |
| 2 | Colisión de hash post-commit: mismo operationId con otro hash cuando origen está `JDBC_COMMITTED`/`REPORT_PENDING` puede degradar la operación | `OperationStateMachine.java` | Bloquear transición a `DLQ_QUARANTINED` desde `JDBC_COMMITTED`/`REPORT_PENDING` por colisión de hash |
| 3 | SDK Vertex no especificado | `pom.xml` | Usar `com.google.genai:google-genai` (Google Gen AI SDK para Java) |
| 4 | Modelo `gemini-2.5-flash` retira 20/10 | `application-dev-vertex.properties` | Confirmar modelo alternativo disponible antes de la fecha de integración |
| 5 | Binding `app.governor.mode` no verificado | `WorkerProperties.java` | Verificar que `@ConfigurationProperties(prefix="app")` bindea correctamente el campo escalar `Mode governor` |

---

## Resumen ejecutivo

| Pregunta de JD | Respuesta |
|---|---|
| ¿Tareas por responsable? | ✅ Tabla completa arriba (5 responsables, 6 frentes) |
| ¿Estado real con evidencia? | ✅ 75/75 tests, 16/16 escenarios, hash intacto, Docker verificado |
| ¿Requisitos de contenedor? | ✅ Dockerfile + .env.example + service accounts + secretos |
| ¿Fecha de integración? | 📅 **20/10** (tentativa) — Go/No-Go 16/10, ensayo 19/10 |
| ¿Dependencias bloqueantes? | ⚠️ Driver jConnect, credenciales, VPN — 3 gates críticos |

---

**Documento preparado por:** Steven Rivera
**Commit de referencia:** `95a73e0` (reporte actualizado con correcciones de Carlos)
**Evidencia:** `docs/evidencia/2026-09-22/`, `docs/pdf-output/Estado_Tecnico_Worker_LNB.pdf`
