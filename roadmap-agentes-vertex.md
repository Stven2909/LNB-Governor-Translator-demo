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
| 2.1 | Selección de modelo | Gemini en `us-central1` (región del entorno) | Plan |
| 2.2 | Prompt + contrato | Prompt construido con el contrato `CONTRACT_PAYMENT_COMMITTED_V0.1` + `catalog_version`; salida en JSON estructurado | Plan |
| 2.3 | Implementar `VertexGovernor` | Nueva implementación de la interfaz `Governor`, conmutada por `@ConditionalOnProperty` para convivir con `MockGovernor` en local | Plan |
| 2.4 | Validación anti-alucinación | Reusar `Catalog.validateGovernor`: si el modelo inventa tabla/acción fuera del catálogo → DLQ | Plan |
| 2.5 | Robustez | Timeout/retry del LLM; modelo caído → `RETRYABLE + NACK`; medir latencia y costos | Plan |
| 2.6 | Pruebas | Correr los 16 escenarios con Vertex real: happy path, rechazo del Gobernador, alucinación | Plan |

**DoD F2:** el pipeline completo pasa con Vertex real en DEV y el guard anti-DLQ sigue activo.

## FASE 3 — Pub/Sub real (push al worker)

| # | Tarea | Detalle | Estado |
|---|---|---|---|
| 3.1 | Semántica push | 200 = ACK (sin reintento), 500 = NACK (retry); ACK solo con evidencia persistida/clasificada | Plan |
| 3.2 | Suscripción | Crear `subs-pagaduria-dev-agentes` (push a la URL de Cloud Run) con retry policy | Plan |
| 3.3 | DLQ real | Configurar `topic-pagaduria-dev-deadletter` como destino final de mensajes fallidos | Plan |
| 3.4 | Prueba integral | Publicar los 16 payloads reales al tópico; verificar SUCCEEDED/REJECTED/IDEMPOTENT/DLQ | Plan |
| 3.5 | Sin loop | Confirmar que los estados terminales no se reintentan y que el NACK respeta el retry | Plan |

**DoD F3:** 16 escenarios vía Pub/Sub real con resultados correctos; idempotencia sin doble pago.

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
| 5.2 | Implementar `ResultReporter` HTTP | Cliente real configurable con URL placeholder en DEV; auth OIDC/IAM planificada | Plan |
| 5.3 | Dueño del endpoint | TBD — confirmar con JD/LNB (integración vs agentes) | Abierto |

**DoD F5:** contrato documentado + cliente implementado; pendiente usar la URL real del endpoint.

## FASE 6 — Coordinación (paquetes de salida, no ejecución)

- **A integración (Alex):** DDL del fixture (tablas de la PoC) para Sybase DEV, nota del driver jConnect, y nombres de secretos propuestos.
- **A Alex (confirmaciones de contrato):** `operationId` ↔ `idempotency_key`, códigos finales de `outcome`, ruta + autenticación del endpoint de reporte.
- **A JD:** decisiones de stack/transporte y dueño del endpoint (F5.3).
- **Limpieza:** apagar los fixtures de la PoC cuando se corone en DEV.

---

## Dependencias

- F1 habilita F2–F5 en el entorno desplegado.
- F2.6 y F3.4 requieren haber desplegado (F1).
- F4 no depende de Sybase.
- F5 queda bloqueada por el dueño del endpoint (F5.3, abierto).
- Nada de este roadmap requiere a integración, salvo los paquetes de F6 y el Sybase real (fuera de nuestro alcance).

## Gates externos que hoy bloquean

1. **Dueño del endpoint de reporte (F5.3)** — confirmar con JD/LNB.
2. **Confirmaciones de contrato de Alex** — `operationId`, códigos de `outcome`, ruta/auth del reporte.
3. **Sybase real** — vía equipo de integración (VM `poc-connect-sybase`).
4. **QA/PROD** — provisión de LNB (hoy solo DEV).