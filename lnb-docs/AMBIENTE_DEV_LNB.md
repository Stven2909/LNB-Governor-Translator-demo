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
| Agentes | `carlos@avanzatech.xyz`, `steven@avanzatech.xyz` — `henry@avanzatech.xyz` |

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
| Instancia BD | `lnbcentral_db_dev` — Cloud SQL (PostgreSQL 18.4) |
| Sybase DEV | `192.168.2.14:5000` — conectividad verificada desde `poc-connect-sybase` |
| VM puente | `poc-connect-sybase` (zona `us-central1-a`) |
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

1. **Validación de la Opción B (endpoint de reporte):** los usuarios del equipo de agentes NO tienen acceso a Cloud SQL (solo la SA de ejecución). El Worker no escribe en Cloud SQL; reporta a la API. Coherente con la MÍNIMA PRIVILEGIA de LNB.
2. **DLQ físico real:** `topic-pagaduria-dev-deadletter` es la contraparte de nuestro `DLQ_QUARANTINED`; `subs-pagaduria-dev` es la cola del flujo de pagaduría.
3. **Deploy path completo:** código fuente → Cloud Build → Artifact Registry → Cloud Run, con egress por Direct VPC a `subnet-comercial-dev-lnb-1` y acceso a Sybase DEV vía la VM.
4. **Secretos:** `pago-premios-dev-db-*` solo los lee la SA de ejecución (nada de credenciales en el repo).
5. **API Gateway:** pendiente de crear el gateway del API Master (lado de la API).

## 5. Pendientes
- [ ] Crear gateway del **API Master** (API Gateway).
- [ ] Definir nombres de nuestro servicio Cloud Run / tópicos propios para la prueba (si no se reutilizan los de pagaduría).
- [ ] Confirmaciones de Alex sobre el mecanismo de resultado (ver `MINUTA_ALEX_RESULTADO_V1.md`).

## 6. Referencias
- Correo de Luis (17 sep 2026, consolidación de accesos DEV).
- `LNB_ER_Tecnico_PostgreSQL_DEV_102_v2.html` (ER 102 v2).
- `MINUTA_ALEX_RESULTADO_V1.md` · `CONTRACT_EVENTO_V0.1.md`.