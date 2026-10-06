package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.JdbcExecutionResult;
import com.pagaduriasintetica.worker.contract.JdbcOutcome;
import com.pagaduriasintetica.worker.contract.OperationResult;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.PreparedStatementSpec;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.PushEnvelope;
import com.pagaduriasintetica.worker.contract.ReportOutcome;
import com.pagaduriasintetica.worker.contract.ReportResult;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.governor.GovernorConfigurationException;
import com.pagaduriasintetica.worker.governor.GovernorInvalidResponseException;
import com.pagaduriasintetica.worker.governor.GovernorUnavailableException;
import com.pagaduriasintetica.worker.translator.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orquestador del Worker de la Pagaduría Digital (prueba vertical PoC de LNB), alineado al
 * contrato oficial PAYMENT_COMMITTED de Alex.
 *
 * Pipeline canónico del plan congelado: validar sobre Pub/Sub -> pre-filtro estructural ->
 * pre-filtro del catálogo (REJECTED sin reservar) -> reserva atómica de operationId ->
 * resolver redelivery según estado -> invariante monetaria (validationRules) -> Gobernador ->
 * validar GovernorContract/whitelist -> Traductor -> executeFixtureTransaction -> reporte ->
 * ACK. Regla inviolable: NUNCA invocar al Gobernador antes de reservar operationId.
 *
 * El Worker interpreta el resultado JDBC sin hardcodear la transición:
 * CONFIRMED -> JDBC_COMMITTED, TEMPORARY_FAILURE -> RETRYABLE, UNKNOWN -> IN_DOUBT.
 * La atomicidad local del fixture NO demuestra atomicidad Sybase-almacenamiento del Worker.
 */
@Service
public class WorkerService {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);

    private static final Set<String> TECHNICAL_COLUMNS = Set.of(
            "OPERATION_ID", "TRACE_ID", "EVENT_ID", "PAYLOAD_HASH"
    );

    private final ObjectMapper mapper;
    private final PayloadHasher hasher;
    private final StructuralValidator validator;
    private final Catalog catalog;
    private final Governor governor;
    private final Translator translator;
    private final StateStore store;
    private final JdbcExecutor jdbc;
    private final ResultReporter reporter;
    private final int maxUnknownRetries;
    private final int maxReportAttempts;

    @org.springframework.beans.factory.annotation.Autowired
    public WorkerService(ObjectMapper mapper, PayloadHasher hasher, StructuralValidator validator,
                         Catalog catalog, Governor governor, Translator translator, StateStore store,
                         JdbcExecutor jdbc, ResultReporter reporter,
                         com.pagaduriasintetica.worker.config.WorkerProperties properties) {
        this(mapper, hasher, validator, catalog, governor, translator, store, jdbc, reporter,
                properties.getMaxUnknownRetries(), properties.getMaxReportAttempts());
    }

    public WorkerService(ObjectMapper mapper, PayloadHasher hasher, StructuralValidator validator,
                         Catalog catalog, Governor governor, Translator translator, StateStore store,
                         JdbcExecutor jdbc, ResultReporter reporter,
                         int maxUnknownRetries, int maxReportAttempts) {
        this.mapper = mapper;
        this.hasher = hasher;
        this.validator = validator;
        this.catalog = catalog;
        this.governor = governor;
        this.translator = translator;
        this.store = store;
        this.jdbc = jdbc;
        this.reporter = reporter;
        this.maxUnknownRetries = maxUnknownRetries;
        this.maxReportAttempts = maxReportAttempts;
    }

    public ProcessingOutcome handleRaw(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            return ackDlq("-", "Empty push body");
        }
        PushEnvelope envelope;
        try {
            envelope = mapper.readValue(rawBody, PushEnvelope.class);
        } catch (Exception e) {
            store.recordMalformed("-", "Malformed push envelope JSON");
            return ackDlq("-", "Malformed push envelope JSON");
        }
        if (envelope == null || envelope.message() == null || envelope.message().data() == null) {
            return ackDlq("-", "Envelope missing message.data");
        }
        return handle(envelope);
    }

    public ProcessingOutcome handle(PushEnvelope envelope) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(envelope.message().data());
        } catch (IllegalArgumentException e) {
            return ackDlq("-", "Invalid Base64 envelope data");
        }
        PaymentCommittedEvent event;
        try {
            event = mapper.readValue(bytes, PaymentCommittedEvent.class);
        } catch (Exception e) {
            store.recordMalformed("-", "Malformed JSON payload");
            return ackDlq("-", "Malformed JSON payload");
        }
        if (event == null) {
            return ackDlq("-", "Empty decoded payload");
        }
        String operationId = event.operationId() != null ? event.operationId() : "-";
        List<String> errors = validator.validate(event);
        if (!errors.isEmpty()) {
            store.recordMalformed(operationId, "Structural validation: " + String.join(",", errors));
            return ackDlq(operationId, "Structural validation failed: " + String.join(", ", errors));
        }
        // Pre-filtro del catálogo ANTES de reservar y ANTES de Vertex (step 2 del pipeline).
        if (!catalog.allows(event.aggregateType(), event.eventType())) {
            log.info("Pre-filter REJECTED operationId={} aggregateType={} eventType={}",
                    operationId, event.aggregateType(), event.eventType());
            return ProcessingOutcome.ack(OperationStatus.REJECTED, operationId,
                    "Operation not allowed or entity not authorized");
        }
        String workerTraceId = UUID.randomUUID().toString();
        String hash = hasher.hash(event.operationData());
        log.info("Worker received operationId={} traceId={} eventId={} payloadHash={}",
                operationId, workerTraceId, event.eventId(), hash);

        OperationState initial = new OperationState(
                operationId, event.eventId(), workerTraceId,
                envelope.message().messageId(), hash,
                OperationStatus.PROCESSING, 1, null, null, null);
        // deliveryAttempt es el contador de TRANSPORTE de Pub/Sub (presente cuando la suscripcion
        // tiene DLQ). No forma parte del contrato de negocio, pero es el limite correcto para
        // detener un ciclo de redelivery del reporte sin alterar el attemptNumber.
        int deliveryAttempt = envelope.deliveryAttempt() != null ? envelope.deliveryAttempt() : 0;
        try {
            store.reserveAtomic(initial);
        } catch (DuplicateOperationException d) {
            return handleRedelivery(event, hash, workerTraceId, deliveryAttempt);
        }
        return processFresh(event, hash, workerTraceId, deliveryAttempt);
    }

    // Colisión de PK = duplicado/en-vuelo por diseño (prohibido check-then-act). La decisión
    // depende del estado persistido: JDBC confirmado -> solo reintentar reporte (nunca JDBC);
    // IN_DOUBT -> conciliación por PAYLOAD_HASH; SUCCEEDED + mismo hash -> ACK idempotente;
    // mismo operationId con distinto hash -> DLQ por colisión de PK.
    private ProcessingOutcome handleRedelivery(PaymentCommittedEvent event, String hash, String workerTraceId,
                                              int deliveryAttempt) {
        String operationId = event.operationId();
        OperationState current = store.get(operationId);
        if (current == null) {
            return ackDlq(operationId, "State lost for in-flight operation");
        }
        if (!current.payloadHash().equals(hash)) {
            log.warn("operationId={} reused with different PAYLOAD_HASH -> quarantine", operationId);
            if (canQuarantine(current.status())) {
                store.markQuarantined(operationId, "operationId collision: different PAYLOAD_HASH");
            }
            return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                    "operationId reused with different payload hash (PK collision)");
        }
        return switch (current.status()) {
            case SUCCEEDED -> ProcessingOutcome.ack(OperationStatus.IDEMPOTENT, operationId,
                    "Duplicate event already processed (PAYLOAD_HASH matched)");
            case DLQ_QUARANTINED -> ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                    "operationId previously quarantined");
            case REJECTED -> ProcessingOutcome.ack(OperationStatus.REJECTED, operationId,
                    "operationId previously rejected");
            case TRANSLATION_ERROR -> ProcessingOutcome.ack(OperationStatus.TRANSLATION_ERROR, operationId,
                    "operationId previously failed translation");
            // BLOCKED_CONFIGURATION y QUARANTINE_TECHNICAL son terminales: la redelivery confirma
            // con ACK en lugar de devolver NACK y reabrir un ciclo que no va a progresar.
            case BLOCKED_CONFIGURATION -> ProcessingOutcome.ack(OperationStatus.BLOCKED_CONFIGURATION, operationId,
                    "operationId blocked by configuration, manual action required");
            case QUARANTINE_TECHNICAL -> ProcessingOutcome.ack(OperationStatus.QUARANTINE_TECHNICAL, operationId,
                    "operationId in technical quarantine after retries exhausted");
            case PROCESSING -> {
                // El lease decide si hay un dueño vivo. Se consulta PRIMERO y sin excepciones:
                // mientras el lease este vigente, esta entrega no toca el estado, aunque ya exista
                // fila en SYNTHETIC_PAYMENTS. Si se mirara la fila primero, una entrega concurrente
                // podria promover a SUCCEEDED la operacion que el dueño original todavia esta
                // ejecutando, y el dueño fallaria al intentar REPORT_PENDING sobre un terminal.
                if (store.leaseIsHeldAndFresh(operationId)) {
                    yield ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                            "Operation in flight (PROCESSING with fresh lease), NACK for scheduled redelivery");
                }
                // Lease vencido o ausente: el intento anterior murio (crash o cierre de instancia).
                // Con fila, el commit SI se aplico: se promueve a in-doubt y luego a SUCCEEDED sin
                // re-ejecutar JDBC. La máquina prohíbe PROCESSING->SUCCEEDED, así que el paso
                // legal es PROCESSING->IN_DOUBT->SUCCEEDED.
                if (paymentMatches(operationId, hash)) {
                    store.markInDoubt(operationId, "Crash residue: commit was applied, promoting in-doubt");
                    store.markSucceeded(operationId);
                    yield ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                            "In-doubt recovery: commit already applied, promoted to SUCCEEDED");
                }
                // Sin fila: la operacion es recuperable. Se reclama (contando un intento nuevo) y se
                // ejecuta el flujo en esta misma entrega, igual que hace RETRYABLE. Si aqui se
                // devolviera NACK, la redelivery siguiente veria el lease recien tomado por este
                // mismo hilo y rebotaria hasta que expirara, quemando un ciclo de lease por intento.
                OperationState reclaimed = store.markProcessing(operationId);
                log.warn("operationId={} abandoned PROCESSING lease reclaimed, attempt={}",
                        operationId, reclaimed.attemptCount());
                yield processFreshClaim(event, hash, workerTraceId, reclaimed, deliveryAttempt);
            }
            case RETRYABLE -> {
                // RETRYABLE puede venir de fallo JDBC temporal (sin fila) o de fallo del reporte
                // (con fila): si la fila ya existe, SOLO se reintenta el reporte (nunca el JDBC).
                if (paymentMatches(operationId, hash)) {
                    yield retryReport(operationId, event, workerTraceId, deliveryAttempt);
                }
                OperationState claimed = store.markProcessing(operationId);
                yield processFreshClaim(event, hash, workerTraceId, claimed, deliveryAttempt);
            }
            case JDBC_COMMITTED, REPORT_PENDING -> retryReport(operationId, event, workerTraceId, deliveryAttempt);
            case IN_DOUBT -> reconcileInDoubt(operationId, hash);
            default -> ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                    "Unexpected status " + current.status());
        };
    }

    private boolean canQuarantine(OperationStatus status) {
        return status == OperationStatus.PROCESSING
                || status == OperationStatus.JDBC_COMMITTED
                || status == OperationStatus.REPORT_PENDING
                || status == OperationStatus.RETRYABLE
                || status == OperationStatus.IN_DOUBT;
    }

    private ProcessingOutcome processFresh(PaymentCommittedEvent event, String hash, String workerTraceId,
                                           int deliveryAttempt) {
        OperationState state = store.get(event.operationId());
        return processFreshClaim(event, hash, workerTraceId, state, deliveryAttempt);
    }

    private ProcessingOutcome processFreshClaim(PaymentCommittedEvent event, String hash, String workerTraceId,
                                                OperationState state, int deliveryAttempt) {
        String operationId = event.operationId();
        // Ultimo recurso del pipeline: una excepcion no prevista (incluida una falla del reporter
        // DENTRO de la rama de exito) no puede quedar oculta ni dejar la reserva en PROCESSING.
        try {
            return executePipeline(event, hash, workerTraceId, state, deliveryAttempt);
        } catch (RuntimeException e) {
            return handleUnhandledFailure(operationId, hash, workerTraceId, deliveryAttempt, e);
        }
    }

    /**
     * Error NO PREVISTO: bug de programacion o dependencia caida.
     *
     * Carlos (revision del Bloque 1): la rama "excepcion no prevista -> RETRYABLE" necesita un
     * limite; sin el, un bug con NACK indefinido genera un ciclo perpetuo en Pub/Sub. Y el
     * destino del agotamiento depende de si el dinero ya se movio:
     *
     * - ANTES del commit: no hay pago, asi que QUARANTINE_TECHNICAL + ACK detiene el ciclo y deja
     *   el mensaje retenido para analisis.
     * - DESPUES del commit: el pago en Sybase ya ocurrio. Poner la operacion en cuarentena tecnica
     *   declararia fallida una operacion cuyo dinero se movio, y el pago quedaria sin registrar.
     *   Se conserva el COMMITTED y se bloquea SOLO el reporte (reportStatus=BLOCKED +
     *   manualActionRequired=true) con ACK, para sacar el mensaje de la suscripcion.
     */
    private ProcessingOutcome handleUnhandledFailure(String operationId, String hash, String workerTraceId,
                                                     int deliveryAttempt, RuntimeException cause) {
        String reason = "Unhandled worker failure: " + cause;
        if (paymentMatches(operationId, hash)) {
            // El commit ya ocurrio. El limite se cuenta con deliveryAttempt (redelivery de Pub/Sub),
            // porque los reintentos de reporte NO incrementan attemptCount y con el solo ese
            // contador el ciclo no terminaria nunca.
            if (deliveryAttempt >= maxReportAttempts) {
                log.error("operationId={} traceId={} post-commit failure at delivery {} of {}, "
                                + "Sybase commit PRESERVED, report blocked, manual action required: {}",
                        operationId, workerTraceId, deliveryAttempt, maxReportAttempts, cause.toString());
                store.markReportBlocked(operationId, reason);
                return ProcessingOutcome.ack(OperationStatus.REPORT_PENDING, operationId,
                        "Post-commit failure exhausted retries: Sybase commit preserved, "
                                + "report blocked, manual action required");
            }
            log.error("operationId={} traceId={} post-commit failure, Sybase commit preserved, "
                    + "scheduled report retry: {}", operationId, workerTraceId, cause.toString());
            store.markReportRetryable(operationId, reason);
            return ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                    "Post-commit failure, scheduled report retry (no JDBC re-run)");
        }
        OperationState current = store.get(operationId);
        int attempts = current != null ? current.attemptCount() : 1;
        if (attempts >= maxUnknownRetries) {
            log.error("operationId={} traceId={} unknown failure exhausted {} attempts, "
                            + "technical quarantine (no commit happened): {}",
                    operationId, workerTraceId, attempts, cause.toString());
            store.markQuarantineTechnical(operationId, reason);
            return ProcessingOutcome.ack(OperationStatus.QUARANTINE_TECHNICAL, operationId,
                    "Unknown failure retries exhausted, technical quarantine");
        }
        log.error("operationId={} traceId={} unhandled worker failure, marking RETRYABLE: {}",
                operationId, workerTraceId, cause.toString());
        store.markRetryable(operationId, reason);
        return ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                "Unhandled worker failure, scheduled retry");
    }

    /**
     * Resultado de invocar al Governor: o un contrato valido, o un fallo ya clasificado y
     * persistido. Evita que las excepciones del Governor se propagen al pipeline.
     */
    private record GovernorCall(GovernorContract contract, ProcessingOutcome failure) {
        static GovernorCall ok(GovernorContract contract) {
            return new GovernorCall(contract, null);
        }

        static GovernorCall failed(ProcessingOutcome outcome) {
            return new GovernorCall(null, outcome);
        }
    }

    /**
     * G4 (revision de Carlos): clasificar el fallo del Governor en cuatro ramas en vez de dejar
     * que la excepcion suba. Antes, un timeout de Vertex devolvia 500 por el framework y dejaba la
     * reserva en PROCESSING; ahora cada rama deja el estado y el ACK/NACK correctos.
     */
    private GovernorCall invokeGovernor(PaymentCommittedEvent event, String workerTraceId) {
        String operationId = event.operationId();
        try {
            return GovernorCall.ok(governor.decide(new GovernorInput(event, workerTraceId)));
        } catch (GovernorUnavailableException e) {
            // Timeout / 429 / 5xx: recuperable. La reserva pasa a RETRYABLE (reclamable) y se NACK
            // para que Pub/Sub programe la redelivery. No se ejecuto JDBC, no hay riesgo de doble pago.
            log.warn("operationId={} traceId={} Governor unavailable: {}",
                    operationId, workerTraceId, e.toString());
            store.markRetryable(operationId, "Governor unavailable: " + e.getMessage());
            return GovernorCall.failed(ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                    "Governor temporarily unavailable, scheduled retry"));
        } catch (GovernorInvalidResponseException e) {
            // JSON invalido o decision fuera de la whitelist: no es transitorio. Cuarentena
            // FUNCIONAL con ACK, sin republicar en el topico de DLQ (que es solo tecnico).
            log.warn("operationId={} Governor response invalid -> functional quarantine: {}",
                    operationId, e.toString());
            store.markQuarantined(operationId, "Governor invalid response: " + e.getMessage());
            return GovernorCall.failed(ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                    "Governor response invalid, functional quarantine"));
        } catch (GovernorConfigurationException e) {
            // Credencial ausente, permiso denegado o modelo no habilitado: no es transitorio ni es
            // culpa del mensaje. BLOCKED_CONFIGURATION con ACK para no reintentar en loop; queda
            // pendiente de accion operativa.
            log.error("operationId={} Governor configuration failure: {}", operationId, e.toString());
            store.markBlockedConfiguration(operationId, "Governor configuration: " + e.getMessage());
            return GovernorCall.failed(ProcessingOutcome.ack(OperationStatus.BLOCKED_CONFIGURATION, operationId,
                    "Governor configuration failure, manual action required"));
        }
    }

    private ProcessingOutcome executePipeline(PaymentCommittedEvent event, String hash, String workerTraceId,
                                               OperationState state, int deliveryAttempt) {
        String operationId = event.operationId();
        // Invariante monetaria determinista (validationRules) con BigDecimal.compareTo: SE
        // VALIDA, NO se recalculan montos. Sin LLM (se evalúa antes del Gobernador).
        if (!monetaryInvariantHolds(event)) {
            store.markRejected(operationId, "validationRules violated: netAmount != grossAmount - withholdingAmount");
            return ProcessingOutcome.ack(OperationStatus.REJECTED, operationId,
                    "Monetary invariant violated (validationRules)");
        }
        GovernorCall governorCall = invokeGovernor(event, workerTraceId);
        if (governorCall.failure() != null) {
            // invokeGovernor ya clasifico el fallo, persistio el estado y decidio el ACK/NACK.
            return governorCall.failure();
        }
        GovernorContract governorContract = governorCall.contract();
        if (governorContract.decision() == GovernorDecision.REJECTED) {
            store.markRejected(operationId, governorContract.reason());
            return ProcessingOutcome.ack(OperationStatus.REJECTED, operationId, governorContract.reason());
        }
        String violation = catalog.validateGovernor(event.aggregateType(), governorContract);
        if (violation != null) {
            store.markQuarantined(operationId, violation);
            return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                    "Governor output violated catalog whitelist");
        }
        TranslatorResult result = translator.translate(new TranslatorInput(event, governorContract, hash, workerTraceId));
        // Guard defensivo del Traductor contra la whitelist (TRANSLATION_ERROR).
        String translationError = validateTranslation(event, result);
        if (translationError != null) {
            store.markTranslationError(operationId, "Translator output violated whitelist: " + translationError);
            return ProcessingOutcome.ack(OperationStatus.TRANSLATION_ERROR, operationId,
                    "Translator output violated catalog whitelist");
        }
        PreparedStatementSpec spec = new PreparedStatementSpec(
                result.sql_template(), governorContract.target_table(), result.parameters(),
                operationId, hash);
        JdbcExecutionResult jdbcResult = jdbc.execute(spec);
        return interpretJdbc(event, hash, workerTraceId, jdbcResult, governorContract.target_table(), state,
                    deliveryAttempt);
    }

    // El Worker interpreta el resultado JDBC (CONFIRMED -> JDBC_COMMITTED, TEMPORARY_FAILURE ->
    // RETRYABLE, UNKNOWN -> IN_DOUBT) de forma declarativa, sin hardcodear la transición.
    private ProcessingOutcome interpretJdbc(PaymentCommittedEvent event, String hash, String workerTraceId,
                                            JdbcExecutionResult jdbcResult, String targetTable,
                                            OperationState state, int deliveryAttempt) {
        String operationId = event.operationId();
        return switch (jdbcResult.outcome()) {
            case TEMPORARY_FAILURE -> {
                store.markRetryable(operationId, "TEMPORARY_FAILURE: " + jdbcResult.detail());
                yield ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                        "JDBC temporary failure, scheduled retry");
            }
            case UNKNOWN -> {
                // commitPaymentInDoubt ya persistió la fila y dejó IN_DOUBT; la redelivery concilia.
                store.markInDoubt(operationId, "UNKNOWN: " + jdbcResult.detail());
                yield ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                        "Indeterminate JDBC result, reconciliation on redelivery");
            }
            case CONFIRMED -> {
                // executeFixtureTransaction ya dejó JDBC_COMMITTED en la base sintética.
                yield reportAfterJdbc(operationId, event, workerTraceId, targetTable, state.attemptCount(),
                            deliveryAttempt);
            }
        };
    }

    private ProcessingOutcome reportAfterJdbc(String operationId, PaymentCommittedEvent event, String workerTraceId,
                                              String targetTable, int attemptCount, int deliveryAttempt) {
        store.markReportPending(operationId);
        OperationResult result = new OperationResult(
                operationId, event.eventId(), event.correlationId(), workerTraceId,
                targetTable, attemptCount, true);
        ReportResult report = reporter.report(result);
        return switch (report.outcome()) {
            case SUCCEEDED -> {
                store.markSucceeded(operationId);
                yield ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                        "Operación validada correctamente: " + report.detail());
            }
            case CONFLICT -> {
                // 409 = "ya reportado": el Worker NO re-ejecuta JDBC; promueve a SUCCEEDED.
                // El detalle del 409 (ALREADY_PROCESSED vs PAYLOAD_CONFLICT vs STATE_CONFLICT)
                // se define en E3.5a; aqui se mantiene el tratamiento de "ya reportado".
                log.info("operationId={} report CONFLICT (already reported), no JDBC re-run", operationId);
                store.markSucceeded(operationId);
                yield ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                        "Report CONFLICT treated as already reported (no JDBC re-run)");
            }
            case FAILED -> {
                yield reportFailed(operationId, deliveryAttempt, "Report failed: " + report.detail());
            }
        };
    }

    // JDBC ya confirmado: en redelivery SÓLO se reintenta el reporte, nunca se re-ejecuta JDBC.
    private ProcessingOutcome retryReport(String operationId, PaymentCommittedEvent event, String workerTraceId,
                                          int deliveryAttempt) {
        OperationState current = store.get(operationId);
        store.markReportPending(operationId);
        OperationResult result = new OperationResult(
                operationId, event.eventId(), event.correlationId(), workerTraceId,
                current.targetTable() != null ? current.targetTable() : "-",
                current.attemptCount(), true);
        ReportResult report = reporter.report(result);
        return switch (report.outcome()) {
            case SUCCEEDED, CONFLICT -> {
                store.markSucceeded(operationId);
                yield ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                        report.outcome() == ReportOutcome.CONFLICT
                                ? "Report CONFLICT treated as already reported (no JDBC re-run)"
                                : "Report retried OK, operation succeeded");
            }
            case FAILED -> {
                yield reportFailed(operationId, deliveryAttempt,
                        "Report failed during redelivery: " + report.detail());
            }
        };
    }

    /**
     * Fallo del reporte con el commit YA confirmado. Principio LNB "commit significa exito":
     * la operacion no se pone en cuarentena tecnica ni se degrada a un estado que niegue el pago.
     * Se reintenta mientras queden redeliverys disponibles y, al agotarlas, el reporte queda
     * BLOCKED con accion manual y se responde ACK para sacar el mensaje de la suscripcion.
     */
    private ProcessingOutcome reportFailed(String operationId, int deliveryAttempt, String reason) {
        if (deliveryAttempt >= maxReportAttempts) {
            log.error("operationId={} report failed at delivery {} of {}: Sybase commit PRESERVED, "
                            + "report blocked, manual action required: {}",
                    operationId, deliveryAttempt, maxReportAttempts, reason);
            store.markReportBlocked(operationId, reason);
            return ProcessingOutcome.ack(OperationStatus.REPORT_PENDING, operationId,
                    "Report retries exhausted: Sybase commit preserved, report blocked, "
                            + "manual action required");
        }
        store.markReportRetryable(operationId, reason);
        return ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                "Report failure, scheduled retry (no JDBC re-run)");
    }

    // IN_DOUBT -> conciliación por PAYLOAD_HASH de SYNTHETIC_PAYMENTS: si la fila existe y el
    // hash coincide, el commit ya se aplicó -> SUCCEEDED (sin reejecutar JDBC); si no, DLQ por
    // inconsistencia.
    private ProcessingOutcome reconcileInDoubt(String operationId, String hash) {
        PaymentRow row = store.findPayment(operationId);
        if (row != null && row.payloadHash().equals(hash)) {
            store.markSucceeded(operationId);
            return ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                    "In-doubt recovery: commit already applied, promoted to SUCCEEDED");
        }
        store.markQuarantined(operationId, "Inconsistent DB: no payment row or PAYLOAD_HASH mismatch for IN_DOUBT");
        return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                "In-doubt reconciliation failed: inconsistent state");
    }

    // ¿La fila SYNTHETIC_PAYMENTS ya existe con el PAYLOAD_HASH correcto? Evita re-ejecutar JDBC
    // en redelivery cuando el commit ya se aplicó (in-doubt / fallo de reporte).
    private boolean paymentMatches(String operationId, String hash) {
        PaymentRow row = store.findPayment(operationId);
        return row != null && row.payloadHash().equals(hash);
    }

    private boolean monetaryInvariantHolds(PaymentCommittedEvent event) {
        var rules = catalog.validationRules(event.aggregateType());
        String rule = rules.get("netAmount");
        if (rule == null) {
            return true;
        }
        var data = event.operationData();
        BigDecimal expected;
        try {
            expected = data.grossAmount().subtract(data.withholdingAmount());
        } catch (NullPointerException e) {
            return false;
        }
        return expected.compareTo(data.netAmount()) == 0;
    }

    private String validateTranslation(PaymentCommittedEvent event, TranslatorResult result) {
        if (result == null) {
            return "translator returned null";
        }
        if (result.sql_template() == null || result.sql_template().isBlank()) {
            return "sql_template missing";
        }
        String aggregateType = event.aggregateType();
        String target = catalog.targetTable(aggregateType);
        String prefix = "INSERT INTO " + target + " ";
        if (!result.sql_template().startsWith(prefix)) {
            return "sql_template must target catalog table " + target;
        }
        if (result.parameters() == null || result.parameters().size() != 12) {
            return "exactly 12 parameters required, got " + (result.parameters() == null ? 0 : result.parameters().size());
        }
        int open = result.sql_template().indexOf('(');
        int close = result.sql_template().indexOf(')', open);
        if (open < 0 || close < 0) {
            return "sql_template malformed column list";
        }
        Set<String> allowed = new HashSet<>(catalog.fieldMapping(aggregateType).values());
        allowed.addAll(TECHNICAL_COLUMNS);
        String columnsPart = result.sql_template().substring(open + 1, close);
        for (String token : columnsPart.split(",")) {
            String column = token.trim();
            if (column.isEmpty()) {
                continue;
            }
            if (!allowed.contains(column)) {
                return "column not in catalog whitelist: " + column;
            }
        }
        return null;
    }

    private ProcessingOutcome ackDlq(String operationId, String reason) {
        String key = operationId != null ? operationId : "-";
        store.recordMalformed(key, reason);
        return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, key, reason);
    }
}