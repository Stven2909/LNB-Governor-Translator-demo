# ROADMAP — Equipo Agentes & Vertex · integración DEV

**Contexto:** proyecto `proy-comercial-dev-lnb` (DEV), región `us-central1`. Acceso provisto según el correo de Luis (mínimo privilegio). Alineado al plan congelado `lnb-docs/PLAN_IMPLEMENTACION_V0.1.md` y al contrato `lnb-docs/CONTRACT_EVENTO_V0.1.md` (`CONTRACT_PAYMENT_COMMITTED_V0.1`).

**Alcance de este roadmap:** solo lo que corresponde al equipo de agentes & Vertex: Cloud Run, Artifact Registry, Cloud Build, Pub/Sub, Vertex AI/Agent Platform y logging/monitoring (solo lectura).

**Fuera de nuestro alcance (según el correo):**
- Sybase real y sus secretos de BD → equipo de integración (Alex).
- API Gateway → no habilitado para el equipo de agentes.
- Cloud SQL, IAM y administración de VPC → no asignados a agentes.
- QA/PROD → solo DEV; la promoción requiere nueva provisión de LNB.
- Valores de secretos → solo los lee la service account de ejecución en runtime.

**Bloques de entorno ya disponibles para nosotros:**
- Deploy desde fuente + Artifact Registry: `us-central1-docker.pkg.dev/proy-comercial-dev-lnb/cloud-run-source-deploy`
- SA de ejecución: `sa-run-agentes-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com`
- SA de build: `sa-build-agentes-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com` (serviceAccountUser sobre estas)
- VPC egress directo: `vpc-comercial-dev-lnb-1` / `subnet-comercial-dev-lnb-1` (10.140.0.0/24)
- Pub/Sub: tópicos existentes `topic-pagaduria-dev` y `topic-pagaduria-dev-deadletter` (flujo de pagaduría); podemos crear tópicos/suscripciones nuevos con retry/DLQ.
- Vertex AI / Agent Platform: habilitado para la SA de agentes.
- Secret Manager: vista de metadata (sin valores).
- Logging / Monitoring: consulta.

**Nota (09/10):** integrados los comentarios de JD/Daniel en ClickUp — E3.2 (JD11), E3.3 (JD12/JD23) y contrato de resultado E3.5 (JD15/JD20). Decisiones y configuraciones no confirmadas quedan **POR CONFIRMAR**.

---

## FASE 0 — Preparación del entorno local → Cloud

| # | Tarea | Estado |
|---|---|---|
| 0.1 | Configurar `gcloud` local: proyecto por defecto `proy-comercial-dev-lnb` + región `us-central1` | Plan |
| 0.2 | Credenciales de desarrollo: Application Default Credentials con la identidad de agentes (para probar Vertex localmente) | Plan |
| 0.3 | Revisar build: JAR de Boot 4.1.x empaquetado; definir Dockerfile o buildpacks para Cloud Run | Plan |
| 0.4 | Llevar el worker a un estado desplegable sin cambios de lógica | Plan |

**DoD F0:** `gcloud run deploy --source` funciona y el worker arranca en Cloud Run.

## FASE 1 — Despliegue del worker en Cloud Run

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 1.1 | Deploy desde fuente | SA de build `sa-build-agentes-dev-lnb`, Artifact Registry `cloud-run-source-deploy`, región `us-central1` | Plan |
| 1.2 | Identidad de ejecución | Correr con `sa-run-agentes-dev-lnb` (tiene Pub/Sub publisher/subscriber + Vertex AI + Cloud SQL client) | Plan |
| 1.3 | VPC egress | Direct VPC egress a `subnet-comercial-dev-lnb-1` (10.140.0.0/24) para salida privada y Sybase futuro | Plan |
| 1.4 | Health + logs | Verificar `/actuator/health` en la URL desplegada y revisar logs en Cloud Logging | Plan |

**DoD F1:** Cloud Run UP, health 200 desde consola, logs visibles.

## FASE 2 — Gobernador real (Vertex AI / Agent Platform)

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 2.1 | Selección de modelo | Gemini en `us-central1` (región del entorno). Registrar el modelo **realmente usado**: el modelo de referencia `gemini-2.5-flash` de laboratorio **no cierra por sí solo** el modelo final cloud; región/modelo/cuotas finales por confirmar | Plan |
| 2.2 | Prompt + contrato | Prompt construido con el contrato `CONTRACT_PAYMENT_COMMITTED_V0.1` + `catalog_version`; salida en JSON estructurado | Plan |
| 2.3 | Implementar `VertexGovernor` | Nueva implementación de la interfaz `Governor`, conmutada por `@ConditionalOnProperty` para convivir con `MockGovernor` en local | Plan |
| 2.4 | Validación anti-alucinación | Reusar `Catalog.validateGovernor`: si el modelo inventa tabla/acción fuera del catálogo → DLQ | Plan |
| 2.5 | Robustez | Timeout/retry del LLM; modelo caído → `RETRYABLE + NACK`; medir latencia y costos | Plan |
| 2.6 | Pruebas | Correr los 16 escenarios con Vertex real: happy path, rechazo del Gobernador, alucinación | Plan |
| 2.7 | Evidencia de cierre | Registrar proyecto, región, modelo realmente usado, versión de prompt/catálogo, contrato de entrada/salida, timeout/cuotas y pruebas de permitido, denegado y error/timeout | Plan |
| 2.8 | Separación lab vs cloud | Documentar el laboratorio y su modelo de referencia separados del modelo cloud | Plan |
| 2.9 | Seguridad (regla JD) | Verificar que los agentes IA no reciben credenciales de BD ni ejecutan JDBC; errores controlados y logs sanitizados | Plan |
| 2.10 | Validación JD11 | Daniel revisa permisos/entorno con Cloudfly (**JD11**); Steven conserva implementación y evidencias; marcar cualquier bloqueo con su dueño | Plan |

**DoD F2:** el pipeline completo pasa con Vertex real en DEV, el guard anti-DLQ sigue activo, la evidencia de 2.7 está adjunta y JD11 está validado.

## FASE 3 — Pub/Sub real (push al worker)

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 3.0 | Cierres distintos | La creación base de topic/subscription y la validación cloud de la integración real tienen **criterios de cierre distintos** | Plan |
| 3.1 | Semántica push | 200 = ACK (sin reintento), 500 = NACK (retry); ACK solo con evidencia persistida/clasificada. Confirmar con Carlos/Cloudfly modalidad de consumo (push/pull), identidad, ACK deadline, retry, DLQ, retención y replay | Plan |
| 3.2 | Suscripción | Crear `subs-pagaduria-dev-agentes` (push a la URL de Cloud Run) con retry policy | Plan |
| 3.3 | DLQ real | Configurar `topic-pagaduria-dev-deadletter` como destino final de mensajes fallidos | Plan |
| 3.4 | Prueba integral | Publicar los 16 payloads reales al tópico; verificar SUCCEEDED/REJECTED/IDEMPOTENT/DLQ | Plan |
| 3.5 | Sin loop | Confirmar que los estados terminales no se reintentan y que el NACK respeta el retry | Plan |
| 3.6 | Evidencia DEV | Demostrar ACK, NACK, reentrega, agotamiento y DLQ con evidencias del entorno real; cada prueba relacionada con su `correlationId` y resultado; validar duplicados y recuperación conforme al contrato aprobado | Plan |
| 3.7 | Validaciones JD | JD12 valida configuración/recuperación (Pub/Sub, suscripciones, DLQ, autenticación del consumidor); JD23 valida DLQ, replay, IN_DOUBT y conciliación; revisión conjunta de casos antes del cierre | Plan |

**DoD F3:** 16 escenarios vía Pub/Sub real con resultados correctos, evidencia DEV por `correlationId` (3.6) y validaciones JD12/JD23 revisadas conjuntamente; idempotencia sin doble pago.

## FASE 4 — Concurrencia real

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 4.1 | Harness con hilos | Redelivery paralela (mismo evento y eventos distintos) contra un store concurrente; validar `reserveAtomic` (putIfAbsent) bajo contención | Plan |
| 4.2 | Documentación | Resultado y throughput; nota: repetir contra Sybase real cuando integración lo provea | Plan |

**DoD F4:** "no pago doble" verificado bajo concurrencia real (múltiples threads/instancias).

## FASE 5 — Contrato de salida (reporte) — nuestra parte es el INPUT

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 5.1 | Documento de contrato | Payload exacto que el Worker emitirá: `eventId`, `attemptNumber`, `outcome`, `payloadHash`, timestamps | Plan |
| 5.2 | Implementar `ResultReporter` HTTP | Cliente real configurable con URL placeholder en DEV; auth OIDC/IAM planificada (Cloudfly apoya IAM) | Plan |
| 5.3 | Dueño del endpoint | **Pendiente** — bloqueo vigente: dueño/ruta/contrato de endpoint interno por confirmar; Daniel coordina el desbloqueo (**JD15**). Henry coordina el acuerdo; Alex desarrolla la API; Carlos mantiene el cliente/Worker; Steven participa en el contrato de resultado | Abierto |
| 5.4 | Idempotencia y sync | Aprobar `eventId+attemptNumber` e `integration.sync_attempt` (PostgreSQL) como parte de la propuesta, con su comportamiento ante duplicados/conflictos | Plan |
| 5.5 | `legacySyncStatus` | Acordar la consulta de estado de sincronización | Plan |
| 5.6 | Aceptación del contrato | Contrato aceptado, endpoint identificado/desplegado y reporte de éxito/error/repetición probado; **tras `JDBC_COMMITTED` una caída del reporte no puede repetir JDBC** | Plan |

**DoD F5:** contrato documentado + cliente implementado + `eventId+attemptNumber`/`integration.sync_attempt` aprobados (JD14/JD15/JD20); pendiente usar la URL real del endpoint. Fechas: 12–19/10, máximo 31/10.

## FASE 6 — Coordinación (paquetes de salida, no ejecución)

- **A integración (Alex):** DDL del fixture (tablas de la PoC) para Sybase DEV, nota del driver jConnect, y nombres de secretos propuestos.
- **A Alex (confirmaciones de contrato):** `operationId` ↔ `idempotency_key`, códigos finales de `outcome`, ruta + autenticación del endpoint de reporte, `eventId+attemptNumber`, `integration.sync_attempt`, `legacySyncStatus`.
- **A JD:** decisiones de stack/transporte y dueño del endpoint (F5.3); tareas JD11/JD12/JD14/JD15/JD20/JD23 (ver `docs/REPORTE_JD_2026-10-06.md` → Relación con tareas JD).
- **Cloudfly:** apoyo IAM para entorno Vertex AI (JD11) y consolidación de IAM/OIDC del reporte.
- **Limpieza:** apagar los fixtures de la PoC cuando se corone en DEV.

---

## Dependencias

- F1 habilita F2–F5 en el entorno desplegado.
- F2.6 y F3.4 requieren haber desplegado (F1).
- F4 no depende de Sybase.
- F5 queda bloqueada por el dueño del endpoint (F5.3, abierto).
- Nada de este roadmap requiere a integración, salvo los paquetes de F6 y el Sybase real (fuera de nuestro alcance).

## Gates externos que hoy bloquean

1. **Dueño del endpoint de reporte (F5.3 / E3.5)** — confirmar con JD/LNB; Daniel coordina desbloqueo (JD15). E3.5 permanece backlog. Fechas: 12–19/10, máximo 31/10.
2. **Confirmaciones de contrato de Alex** — `operationId`, códigos de `outcome`, ruta/auth del reporte, `eventId+attemptNumber`, `integration.sync_attempt`, `legacySyncStatus`, comportamiento ante duplicados/conflictos.
3. **Sybase real** — vía equipo de integración (VM `poc-connect-sybase`).
4. **QA/PROD** — provisión de LNB (hoy solo DEV).
5. **Modelo Vertex final cloud** — región/modelo/cuotas finales por confirmar (JD11 con Cloudfly); `gemini-2.5-flash` retira el 20/10/2026.