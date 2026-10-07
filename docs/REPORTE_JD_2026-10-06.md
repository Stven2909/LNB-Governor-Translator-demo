# Reporte de Estado — Equipo Agentes & Vertex
**Fecha:** 6 de octubre de 2026
**Proyecto:** Pagaduría Digital — prueba vertical LNB
**Contrato:** CONTRACT_PAYMENT_COMMITTED_V0.1

---

## 1. Tareas estructuradas por responsable

| Responsable | Frente | Estado actual | Pendiente | Fecha entrega |
|---|---|---|---|---|
| **Carlos** | Gobernador real (Vertex AI), prompt, contrato con Alex | Scaffold listo (`MockGovernor`, `GovernorContract`, `Catalog.validateGovernor()`) | SDK Vertex en `pom.xml`, prompt estructurado, validación anti-alucinación | 16/10 (review técnica) |
| **Steven** | Recorrido del worker, ACK/NACK, errores/reintentos, reporte a la API, publicador Outbox→Pub/Sub | 75/75 tests, 16/16 escenarios, `PushController` + `WorkerService` + estados | SDK Vertex (depende de Carlos), reporte HTTP real, publicador Outbox | 19/10 (ensayo integral) |
| **Henry** | Arquitectura, monitoreo, seguimiento de entrega | Health checks configurados, logs en Cloud Logging, plantillas de despliegue | Evidencia de monitoreo en DEV, dashboard de estado | 14/10 |
| **JD/LNB** | Infraestructura: Pub/Sub, contenedores, VPN, accesos | Accesos habilitados en `proy-comercial-dev-lnb` | Suscripción push + `--invoker`, VPN Cloud Run→Sybase, credenciales Secret Manager | 14-15/10 |
| **Alex** | PostgreSQL/Sybase, driver jConnect, DDL, credenciales | VM `poc-connect-sybase` verificada (conectividad TCP a `192.168.2.14:5000`) | Driver jConnect (procedencia/licencia), credenciales BD, tablas Sybase DEV | Pendiente confirmación |

---

## 2. Estado real con evidencia

### Lo que YA funciona (verificado hoy)

| Componente | Evidencia | Resultado |
|---|---|---|
| **Suite de pruebas** | `mvn -B test` | **75/75 tests en verde** (9 clases) |
| **Harness E2E** | `DemoEvidenceTest` — 16 escenarios | **16/16 PASS** |
| **Hash canónico** | SHA-256 del payload de referencia | `0fd5240accd7b41a9d55d95567eb79b0f8f87a9776c0396a5f888d9c7a99c246a8` — intacto |
| **Imagen Docker** | `docker build -t worker-poc:dev .` | Build exitoso, 486 KB |
| **Health en contenedor** | `docker run` + `curl /actuator/health/readiness` | `{"status":"UP"}` |
| **Usuario no-root** | `docker exec worker-test id` | `uid=1001(lnb)` |
| **Binding de variables** | `APP_MAX_UNKNOWN_RETRIES=99` → falla arranque por `@Max(20)` | Confirmado |
| **Demo HTTP** | 7 POSTs a `/push` | 7/7 HTTP 200 |

### Desglose de las 75 pruebas

| Clase | Tests | Cobertura |
|---|---|---|
| `WorkerPipelineTest` | 24 | Happy paths, idempotencia, in-doubt, rechazos, DLQ |
| `CommitMeansSuccessTest` | 12 | Frontera "commit significa éxito" |
| `GovernorFailureClassificationTest` | 8 | Clasificación G4 de errores del Governor |
| `ConcurrentRedeliveryTest` | 7 | Lease, redelivery paralela, sin doble pago |
| `WorkerPropertiesTest` | 11 | Validación fail-fast de configuración |
| `BranchingCoverageTest` | 4 | Cobertura de ramas críticas |
| `OperationStateMachineTest` | 4 | Transiciones válidas/inválidas |
| `PayloadHasherTest` | 3 | Hash canónico del contrato |
| `DemoEvidenceTest` | 1 | Harness E2E de 16 escenarios |

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
| Vertex AI (Gobernador real) | Mock | Falta SDK + prompt de Carlos |
| Pub/Sub real (push) | HTTP simulado | Falta suscripción + `--invoker` de JD |
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
| `GOOGLE_APPLICATION_CREDENTIALS` | — | Ruta al JSON de service account |

### Accesos necesarios

| Recurso | Service account | Rol requerido | Estado |
|---|---|---|---|
| Cloud Run (despliegue) | `sa-build-agentes-dev-lnb@…` | Cloud Run Admin + Artifact Registry Writer | ✅ Habilitado |
| Cloud Run (ejecución) | `sa-run-agentes-dev-lnb@…` | Cloud Run Invoker + Pub/Sub Subscriber + Vertex AI User | ✅ Habilitado |
| Pub/Sub | `sa-run-agentes-dev-lnb@…` | Pub/Sub Publisher + Subscriber | ✅ Habilitado |
| Vertex AI | `sa-run-agentes-dev-lnb@…` | Vertex AI User | ✅ Habilitado |
| Secret Manager | `sa-run-agentes-dev-lnb@…` | Secret Manager Secret Accessor | ⏳ Pendiente (solo metadata visible) |
| Cloud Run push (OIDC) | `sa-run-agentes-dev-lnb@…` | `roles/run.invoker` sobre el servicio | ⏳ Pendiente (JD habilita) |

### Secretos (Secret Manager)

| Nombre | Uso | Estado |
|---|---|---|
| `REPORT_API_TOKEN` | Token para el endpoint de reporte | ⏳ Pendiente (valor en Secret Manager) |
| `SYBASE_PASSWORD` | Contraseña de Sybase | ⏳ Pendiente (valor en Secret Manager) |

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
| 3 | Suscripción Pub/Sub push + `--invoker` | JD | ⏳ Pendiente | **MEDIO** — bloquea E3.3 pero no E3.4 |
| 4 | VPN Cloud Run → Sybase | JD | ⏳ Pendiente | **ALTO** — sin VPN no hay conectividad |
| 5 | SDK Vertex + prompt | Carlos | ⏳ Pendiente | **MEDIO** — bloquea E3.2 pero no E3.4 |
| 6 | Contrato 409 del reporte | Alex | ⏳ Pendiente | **BAJO** — no bloquea la prueba de Sybase |
| 7 | Publicador Outbox → Pub/Sub | Steven (propuesta) | ⏳ Pendiente | **MEDIO** — bloquea la cadena completa |

### Criterio Go/No-Go (16 de octubre)

**Se mantiene el 20/10 si:**
- [ ] Driver jConnect recibido y validado por Alex
- [ ] Credenciales Secret Manager operativas
- [ ] VPN Cloud Run → Sybase verificada
- [ ] SDK Vertex + prompt funcionales (Carlos)
- [ ] Suscripción Pub/Sub push configurada (JD)

**Se mueve al 27/10 si:**
- Cualquiera de los 3 primeros gates (driver, credenciales, VPN) no está listo

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
**Commit de referencia:** `4d3c3e1`
**Evidencia:** `docs/evidencia/2026-09-22/`, `docs/pdf-output/Estado_Tecnico_Worker_LNB.pdf`
