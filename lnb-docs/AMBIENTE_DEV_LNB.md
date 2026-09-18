# AMBIENTE_DEV_LNB

**Estado:** Confirmado por Luis (correo consolidado del 17 septiembre 2026)
**Alcance:** Solo `proy-comercial-dev-lnb` (DEV) — sin acceso a QA ni PROD
**Criterio:** Mínimo privilegio; identidades técnicas separadas por equipo y roles acotados al recurso
**Fuente:** Correo de Luis (consolidación de los 3 correos de solicitud de acceso)

---

## 1. Equipos e identidades

| Equipo | Correos |
|---|---|
| Integración | `alex@avanzatech.xyz`, `it@avanzatech.xyz` |
| Agentes | `carlos@avanzatech.xyz`, `steven@avanzatech.xyz` — `henrry@avanzatech.xyz` (pendiente confirmar existencia/corrección) |

## 2. Permisos por equipo (usuarios)

### Equipo de integración
- **Cloud Run:** consultar, crear, actualizar y desplegar servicios, incl. desde código fuente.
- **Artifact Registry:** repo `cloud-run-source-deploy` (Docker, us-central1) del proyecto DEV.
- **Cloud Build:** ejecutar y consultar builds; la SA de build puede publicar en Artifact Registry y desplegar en Cloud Run.
- **Service Accounts:** ver cuentas y usar las de su equipo.
- **Service Usage:** consumir y habilitar APIs (ya habilitadas: Vertex AI, API Gateway, Service Control, Cloud Asset).
- **Cloud Asset Inventory:** consulta.
- **Networking:** lectura a VPC/subredes/firewall; usar la subred DEV desde Cloud Run (Direct VPC egress). Sin administración general de red.
- **Pub/Sub:** crear/configurar tópicos y suscripciones, publicar y consumir, configurar retry/DLQ.
- **Secret Manager:** ver secretos y metadata (NO valores). La SA de ejecución sí lee los secretos de BD (`pago-premios-dev-db-*`).
- **API Gateway:** crear y actualizar APIs, configuraciones y gateways.
- **Logging/Monitoring:** consulta.
- **VM `poc-connect-sybase`:** ver y entrar por SSH/IAP desde consola. Sin apagar/eliminar/recrear.

### Equipo de agentes
- **Cloud Run / Artifact Registry / Cloud Build:** mismo alcance que integración, incl. despliegue desde código fuente.
- **Pub/Sub:** crear/configurar tópicos y suscripciones, publicar y consumir, retry/DLQ.
- **Vertex AI / Agent Platform:** uso de recursos de Vertex AI.
- **Service Accounts:** `serviceAccountUser` solo sobre identidades de su equipo.
- **Service Usage:** consumo de APIs habilitadas.
- **Logging, Monitoring, Networking:** solo lectura.
- **Secret Manager:** ver secretos (NO valores).
- **SIN ACCESO:** Cloud SQL, Sybase, IAM, administración de VPC (para usuarios).

## 3. Recursos confirmados

| Recurso | Valor |
|---|---|
| Proyecto | `proy-comercial-dev-lnb` |
| Instancia Cloud SQL | `sql-comercial-dev-lnb` (us-central1) |
| Base de datos | `lnbcentral_db_dev` — PostgreSQL 18.4 |
| Sybase DEV | `192.168.2.14:5000` — conectividad verificada **desde la VM** `poc-connect-sybase` |
| VM de conectividad | `poc-connect-sybase` (zona `us-central1-a`) — confirma TCP a Sybase; NO es puente automático Cloud Run→Sybase |
| SA Cloud Run — integración | `sa-run-integracion-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com` |
| SA Cloud Run — agentes | `sa-run-agentes-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com` |
| SA Build — integración | `sa-build-integracion-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com` |
| SA Build — agentes | `sa-build-agentes-dev-lnb@proy-comercial-dev-lnb.iam.gserviceaccount.com` |
| Artifact Registry | `us-central1-docker.pkg.dev/proy-comercial-dev-lnb/cloud-run-source-deploy` |
| VPC / subred (egress privado) | `vpc-comercial-dev-lnb-1` / `subnet-comercial-dev-lnb-1` (`10.140.0.0/24`, us-central1) |
| Pub/Sub existente | `topic-pagaduria-dev` + `topic-pagaduria-dev-deadletter` (subs `subs-pagaduria-dev` y `-deadletter`) — flujo de pagaduría; pueden crearse tópicos nuevos |
| API Gateway | API habilitada; **ningún gateway creado aún** (a crear el del API Master) |
| Secretos BD | `pago-premios-dev-db-*` (lee la SA de ejecución) |

### Permisos de las service accounts de ejecución
- **Integración:** Cloud SQL client · Pub/Sub publisher/subscriber · lectura de secretos de BD · invocación de Cloud Run (para que API Gateway llame a backends).
- **Agentes:** Cloud SQL client · Pub/Sub publisher/subscriber · Vertex AI.

## 4. Implicaciones para la arquitectura del Worker/Prueba express

1. **Recomendación de la Opción B (endpoint de reporte):** los usuarios del equipo de agentes NO tienen acceso a Cloud SQL, pero la SA de ejecución SÍ tiene `Cloud SQL client` (conexión técnica posible). El diseño **propone** que el Worker no escriba directo en Cloud SQL y reporte a la API por mínimo privilegio; no está "validada" aún (pendiente confirmación con la API).
2. **DLQ de transporte vs. estado lógico:** `topic-pagaduria-dev-deadletter` es un tópico de la infraestructura de pagaduría; NO está confirmado que `subs-pagaduria-dev` tenga política dead-letter configurada. Nuestro `DLQ_QUARANTINED` es un estado lógico del Worker en `WORKER_OPERATION_STATE`, no un tópico. Si se requiere reencaminar eventos a un DLQ propio, es trabajo aparte (crear tópico/suscripción de la prueba).
3. **Deploy path completo:** código fuente → Cloud Build → Artifact Registry → Cloud Run, con egress por Direct VPC a `subnet-comercial-dev-lnb-1`. La conectividad **Cloud Run → Sybase NO está verificada**: la VM confirmó TCP a `192.168.2.14:5000`, pero conferir esa ruta a Cloud Run (Direct VPC / Serverless VPC Access / VPN) está por demostrar.
4. **Secretos:** `pago-premios-dev-db-*` parecen ser de la BD de pagaduría (Cloud SQL), **no asumir** credenciales Sybase. Solo los lee la SA de ejecución; nada de credenciales en el repo. Si la integración real requiere credenciales Sybase, solicitar secretos nuevos a Luis.
5. **API Gateway:** pendiente de crear el gateway del API Master (lado de la API).

## 5. Idempotencia durable del Worker (ABIERTA)

LNB no define aún dónde persiste el Worker su estado de idempotencia (`WORKER_OPERATION_STATE`). Alternativas candidatas (ninguna confirmada; no asumir):

| Alternativa | Nota |
|---|---|
| Cloud SQL (tabla propia) | Existe en DEV; requiere plantear el acceso/uso con la API |
| Endpoint de reserva administrado por la API | Variante del API Master; por confirmar |
| Almacenamiento propio del Worker | Depende del runtime (Cloud Run sin filesystem durable) |
| Tabla de control en Sybase | Pendiente decisión LNB; si existe, no confirmada |

Esta decisión NO bloquea el alineamiento del PoC (fixture en memoria), sí lo hace la implementación real.

## 6. Pendientes
- [ ] Confirmar usuario `henrry@avanzatech.xyz` (no encontrado en Google; posible variante `henry@`) — Luis.
- [ ] Confirmar si se reutilizan los tópicos/suscripciones de pagaduría o se crean tópicos propios de la prueba.
- [ ] Confirmar si `subs-pagaduria-dev` tiene política dead-letter configurada.
- [ ] Definir dónde persiste el Worker su idempotencia durable (sección 5).
- [ ] Demostrar conectividad **Cloud Run → Sybase DEV** (Direct VPC / Serverless VPC Access / VPN); la VM solo confirma TCP desde ella.
- [ ] Solicitar secretos/cuenta para credenciales Sybase DEV si la integración real lo requiere.
- [ ] Confirmar con integración el uso de `sa-run-integracion-dev-lnb` vs. una SA propia del equipo de agentes para el Cloud Run del Worker.
- [ ] Crear gateway del **API Master** (API Gateway).
- [ ] Confirmaciones de Alex sobre el mecanismo de resultado (ver `MINUTA_ALEX_RESULTADO_V1.md`).

## 7. Referencias
- Correo de Luis (17 sep 2026, consolidación de accesos DEV).
- `LNB_ER_Tecnico_PostgreSQL_DEV_102_v2.html` (ER 102 v2).
- `MINUTA_ALEX_RESULTADO_V1.md` · `CONTRACT_EVENTO_V0.1.md`.