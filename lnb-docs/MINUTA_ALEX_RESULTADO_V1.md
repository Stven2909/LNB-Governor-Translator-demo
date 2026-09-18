# MINUTA_ALEX_RESULTADO_V1

**Estado:** ENVIADA a Alex el 17 septiembre 2026 (a la espera de confirmaciones)
**Versión:** V1 (correcciones integradas: idempotencia durable pre-JDBC, correlationId comparativo, destination_confirmed_at por API, tiempos de sync_attempt, IN_DOUBT, idempotencia Sybase pendiente de LNB)
**Fecha:** 17 septiembre 2026
**Responsables:** Carlos Herrera (autor) — en coordinación con Steven y Henry
**Referencias:** `LNB_ER_Tecnico_PostgreSQL_DEV_102_v2.html` (ER 102 v2) · `CONTRACT_EVENTO_V0.1.md` · `CATALOG_PAYMENT_COMMITTED_V0.1.json`

---

# Correo

**Buen día, Alex:**

Gracias por las confirmaciones sobre el contrato del evento, los identificadores y el flujo Outbox → Pub/Sub, y por abrir la coordinación del mecanismo de resultado.

Proponemos para el MVP un **endpoint interno de reporte administrado por la API**, lo que mantiene la separación de responsabilidades:
- La API conserva la propiedad y las reglas del esquema `integration`.
- El Worker no requiere acceso directo ni permisos de escritura sobre Cloud SQL — bajo acoplamiento: depende del contrato, la disponibilidad y la autenticación del endpoint.
- La API registra el intento en `integration.sync_attempt` y actualiza `integration.outbox_event` dentro de una transacción PostgreSQL.
- El estado registrado alimenta `legacySyncStatus` en `GET /payments/{paymentId}`.

**Nuestro flujo propuesto:**
1. El Worker recibe `PAYMENT_COMMITTED` y hace la **reserva idempotente durable** del evento (antes de Gobernador/Traductor/JDBC).
2. El Gobernador y el Traductor validan y preparan la operación.
3. El módulo JDBC ejecuta la operación contra Sybase.
4. El Worker reporta el resultado al endpoint interno (reintentando solo el reporte si el COMMIT de Sybase ya ocurrió).
5. La API registra `sync_attempt` y actualiza `outbox_event`/`destination_confirmed_at` transaccionalmente.
6. Con 2xx exitoso de la API, el Worker confirma el mensaje de Pub/Sub.

> **Consistencia:** no existe una transacción única entre Sybase y PostgreSQL (`COMMIT Sybase → reportar → COMMIT PostgreSQL`). El endpoint maneja esa consistencia de forma controlada, idempotente y auditable; como Pub/Sub Push es at-least-once, el diseño asume redelivery posible.

**1. Garantía durable de que JDBC ya se ejecutó.**
La idempotencia del reporte evita duplicar `sync_attempt`, pero no evita que Sybase reciba la operación dos veces ante una reentrega. Por eso, **antes del JDBC**, el Worker conserva un registro durable del evento con estados:

`RECEIVED → PROCESSING → JDBC_COMMITTED → REPORT_PENDING → SUCCEEDED`

y ante una reentrega de Pub/Sub:
- **Evento nuevo:** Gobernador → Traductor → JDBC.
- **`SUCCEEDED`:** ACK sin ejecutar nada.
- **`JDBC_COMMITTED` / `REPORT_PENDING`:** reintentar únicamente el reporte.
- **`IN_DOUBT`:** no reejecutar JDBC; pasar a conciliación/revisión manual.

El módulo JDBC ejecuta la operación contra Sybase. La reserva y el estado de procesamiento se conservan en almacenamiento durable del Worker; al recibir el resultado JDBC, el Worker actualiza su estado a `JDBC_COMMITTED`, `RETRYABLE` o `IN_DOUBT` según corresponda. Esta actualización **no constituye una transacción única con Sybase**.

En nuestra implementación, la reserva idempotente y los estados de procesamiento se mantienen en almacenamiento durable del Worker (en el fixture DEV, co-ubicados en la base Sybase DEV con `OPERATION_ID` como clave única, confirmados junto al INSERT como evidencia del mecanismo). Este diseño evita el procesamiento concurrente y permite continuar solo con el reporte ante una reentrega, pero **no vuelve atómica** la escritura entre el almacenamiento del Worker y Sybase.

Distinguimos tres niveles de idempotencia:

| Nivel | Clave propuesta | Evita |
|---|---|---|
| Procesamiento del Worker | `operationId` | Dos procesos concurrentes del mismo pago |
| Reporte a la API | `eventId + attemptNumber` | Duplicar `sync_attempt` |
| Ejecución en Sybase | Pendiente de LNB | Duplicar el pago en el sistema legacy |

Los dos primeros están definidos. El tercero está pendiente: del lado de LNB queda por coordinar el mecanismo de idempotencia en Sybase —clave única, tabla de control o procedimiento autorizado— para impedir una segunda ejecución con el mismo `operationId`. No asumimos que esa estructura exista actualmente en el destino real; mientras no exista, cualquier resultado incierto alrededor del COMMIT se clasificará como `IN_DOUBT` y se conciliará antes de reintentar.

El endpoint de reporte **no reemplaza** esta idempotencia previa al Gobernador. Aceptamos mantener la reserva en el almacenamiento del Worker; si la API prefiriera ofrecer esa "reserva de operación" como servicio propio, lo alinearíamos.

**2. Endpoint de reporte idempotente.**
Clave `eventId + attemptNumber`, espejo del constraint del modelo [**Verificado en ER 102 v2: `uq_sync_attempt UNIQUE(outbox_event_id, attempt_number)`**]. Misma clave + mismo contenido → mismo resultado exitoso; misma clave con contenido distinto → rechazo por conflicto (HTTP 409).

**3. Contrato del reporte (camelCase) y su tratamiento en PostgreSQL:**

| Campo | En PostgreSQL | Tratamiento |
|---|---|---|
| `eventId` | `outbox_event.outbox_event_id` (PK) | Clave del reporte (1/n) |
| `attemptNumber` | `sync_attempt.attempt_number` | Clave (n), `> 0` |
| `operationId` | contextual → asumimos `outbox_event.idempotency_key` **[a confirmar]** | Referencia (ver nota final) |
| `paymentId` | contextual (`outbox_event.aggregate_id` / `payload_json`) | No se persiste en `sync_attempt` |
| `outcome` | `sync_attempt.outcome_code` | Vocabulario propuesto `SUCCEEDED`, `RETRYABLE`, `REJECTED`, `IN_DOUBT` — confirmen los códigos finales |
| `destinationStatus` | `sync_attempt.destination_status_code` | Solo si Sybase reportó estado |
| `responseReference` | `sync_attempt.response_reference` | Ref. emitida por Sybase |
| `errorCode` | `sync_attempt.error_code` | |
| `errorMessage` | `sync_attempt.error_message` | Limitado y sanitizado (sin SQL, sin payloads completos, sin datos sensibles) |
| `startedAt` | `sync_attempt.started_at` | Inicio del intento |
| `finishedAt` | `sync_attempt.finished_at` | Fin del intento; la API valida `finishedAt >= startedAt` |
| `latencyMs` | `sync_attempt.latency_ms` | `>= 0` |
| `traceId` | `sync_attempt.trace_id` | Traza interna del Worker |
| `correlationId` | — | **Se compara** contra `outbox_event.correlation_id`; una diferencia se rechaza como conflicto (no debe modificarse) |
| `payloadHash` | — | Contextual/opcional; en nuestro Worker se persiste en el estado de operación para reconciliación |
| `destinationOutcomeAt` | — | El Worker solo informa cuándo Sybase respondió; **la API** fija `destination_confirmed_at` al aceptar un resultado que confirma el destino, y queda `NULL` para `RETRYABLE`, `REJECTED` e `IN_DOUBT` |

**4. IN_DOUBT.**
Si Sybase **confirmó** la transacción y únicamente falló el endpoint, el Worker reintentará **solo el reporte**. Si el resultado de la transacción es **indeterminado**, el Worker registrará `IN_DOUBT`, **no repetirá JDBC** y enviará la operación a conciliación o revisión manual. Un `REJECTED` del Gobernador no genera necesariamente un intento contra Sybase (pregunta 6 abajo).

**5. Autenticación.**
El endpoint será privado y sin exposición pública. Proponemos identidad de servicio + IAM + ID token OIDC; si el ingreso es vía API Gateway en lugar de Cloud Run directo, pedimos a ustedes (y Cloudfly) confirmar el mecanismo exacto.

**Preguntas para cerrar:**
1. ¿Están de acuerdo con el endpoint interno como mecanismo del MVP?
2. ¿La API registraría `integration.sync_attempt` y actualizaría `integration.outbox_event` dentro de una misma transacción?
3. ¿Confirmamos `eventId + attemptNumber` como clave idempotente del reporte?
4. ¿La reserva durable previa al JDBC la mantenemos en el almacenamiento del Worker (opción A) o prefieren ofrecer una operación previa desde la API (opción B)?
5. ¿Qué ruta, autenticación, estados y formato definitivo tendrá el endpoint?
6. ¿El endpoint recibirá solo resultados de ejecución contra Sybase o también decisiones previas como `REJECTED`?
7. ¿El estado persistido será la fuente de `legacySyncStatus` para la consulta del pago?

**Nota final (cierre del contrato de evento):** en el ER 102 v2, `outbox_event` no tiene columna `operation_id`; el unique de idempotencia es `uq_outbox_idempotency UNIQUE(destination_system_code, idempotency_key)`. Asumimos `operationId` (Idempotency-Key) → `outbox_event.idempotency_key`. ¿Confirmas esa correspondencia o habrá columna propia?

Como evolución posterior evaluaremos un evento de resultado (`PAYMENT_LEGACY_SYNCED`), publicando la API por su propio Outbox en lugar de que el Worker escriba en el Outbox de PostgreSQL.

Quedamos atentos para cerrar el contrato y avanzar con el Worker.
Saludos, **Carlos Herrera** — En coordinación con Steven y Henry.

---

## Cambios incorporados respecto a la versión previa
1. Sustituida la afirmación de "misma transacción Sybase" por la redacción de almacenamiento durable del Worker (reserva y estados no comparten transacción con Sybase).
2. Añadida la distinción de los tres niveles de idempotencia (Worker / reporte / Sybase) y el **pendiente de LNB** sobre idempotencia en el destino legacy.
3. `correlationId` se compara (no se modifica) — rechazo por conflicto si no coincide.
4. `destination_confirmed_at` la fija la API (`destinationOutcomeAt` informativo; `NULL` salvo confirmación).
5. Tiempos explícitos `startedAt/finishedAt/latencyMs` alineados a `sync_attempt` (`finished_at >= started_at`).
6. Redacción de `IN_DOUBT` corregida (sin reintento JDBC; conciliación manual).
7. Vocabulario `SUCCEEDED` (no SUCCESS) a confirmar por Alex; `errorMessage` sanitizado; nota OIDC/IAM condicionada al punto real de entrada (Cloud Run vs API Gateway).