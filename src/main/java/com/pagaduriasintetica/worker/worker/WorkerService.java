package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.PushEnvelope;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.translator.Translator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Orquestador del Worker de la Pagaduría Digital (prueba vertical PoC de LNB).
 * Pipeline: push Pub/Sub -> decode Base64 -> validación estructural -> PAYLOAD_HASH canónico ->
 * reserva atómica en WORKER_OPERATION_STATE (PK operationId) -> pre-filtro del catálogo
 * (REJECTED sin invocar a Vertex) -> Gobernador (Vertex AI) -> validateGovernor (DLQ si alucina) ->
 * Traductor (guard TRANSLATION_ERROR) -> commit a SYNTHETIC_PAYMENTS (mock del Sybase DES) ->
 * ACK solo tras persistir evidencia.
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

    public WorkerService(ObjectMapper mapper, PayloadHasher hasher, StructuralValidator validator,
                         Catalog catalog, Governor governor, Translator translator, StateStore store) {
        this.mapper = mapper;
        this.hasher = hasher;
        this.validator = validator;
        this.catalog = catalog;
        this.governor = governor;
        this.translator = translator;
        this.store = store;
    }

    public ProcessingOutcome handleRaw(String rawBody) {
        // Punto de entrada HTTP: nunca confiar en el cuerpo; ACK solo tras persistir evidencia.
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
        SyntheticEvent event;
        try {
            event = mapper.readValue(bytes, SyntheticEvent.class);
        } catch (Exception e) {
            store.recordMalformed("-", "Malformed JSON payload");
            return ackDlq("-", "Malformed JSON payload");
        }
        if (event == null) {
            return ackDlq("-", "Empty decoded payload");
        }
        List<String> errors = validator.validate(event);
        if (!errors.isEmpty()) {
            store.recordMalformed(event.operationId() != null ? event.operationId() : "-", "Structural validation: " + String.join(",", errors));
            return ackDlq(event.operationId() != null ? event.operationId() : "-", "Structural validation failed: " + String.join(", ", errors));
        }
        String operationId = event.operationId();
        String hash = hasher.hash(event.payload());
        log.info("Worker received operationId={} traceId={} eventId={} payloadHash={}", operationId, event.traceId(), event.eventId(), hash);

        OperationState initial = new OperationState(
                operationId, event.eventId(), event.traceId(),
                envelope.message().messageId(), hash,
                OperationStatus.PROCESSING, 1, null, null, null);
        try {
            store.reserve(initial);
        } catch (DuplicateOperationException d) {
            return handleDuplicate(event, hash);
        }
        return processReserved(event, hash, store.get(operationId));
    }

    // Colisión de PK = duplicado/en-vuelo por diseño (prohibido check-then-act).
    private ProcessingOutcome handleDuplicate(SyntheticEvent event, String hash) {
        String operationId = event.operationId();
        OperationState current = store.get(operationId);
        if (current.payloadHash().equals(hash)) {
            if (current.status() == OperationStatus.SUCCEEDED) {
                return ProcessingOutcome.ack(OperationStatus.IDEMPOTENT, operationId, "Duplicate event already processed (PAYLOAD_HASH matched)");
            }
            if (current.status() == OperationStatus.DLQ_QUARANTINED) {
                return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId, "operationId previously quarantined");
            }
            PaymentRow row = store.findPayment(operationId);
            if (row != null) {
                if (row.payloadHash().equals(hash)) {
                    store.promoteInDoubt(operationId, new OperationState(
                            operationId, event.eventId(), event.traceId(), current.messageId(), hash,
                            OperationStatus.SUCCEEDED, current.attemptCount(), "APPROVED", row.targetTable(), null));
                    return ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId,
                            "In-doubt recovery: commit already applied, promoted to SUCCEEDED");
                }
                store.update(current.withStatus(OperationStatus.DLQ_QUARANTINED)
                        .withError("Inconsistent DB: payment row exists with different PAYLOAD_HASH"));
                return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                        "Inconsistent DB: payment payload hash mismatch");
            }
            if (current.status() == OperationStatus.PROCESSING) {
                store.update(current.bumped());
                return ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                        "Operation in flight (PROCESSING), NACK for scheduled redelivery");
            }
            if (current.status() == OperationStatus.RETRYABLE) {
                OperationState owner = current.bumped().withStatus(OperationStatus.PROCESSING);
                store.update(owner);
                return processReserved(event, hash, owner);
            }
            return ProcessingOutcome.nack(OperationStatus.RETRYABLE, operationId,
                    "Unexpected status " + current.status());
        }
        store.update(current.withStatus(OperationStatus.DLQ_QUARANTINED)
                .withError("operationId collision: different PAYLOAD_HASH"));
        return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                "operationId reciclado con datos distintos (PK collision)");
    }

    private ProcessingOutcome processReserved(SyntheticEvent event, String hash, OperationState state) {
        String operationId = event.operationId();
        // Pre-filtro determinista contra el catálogo: REJECTED directo sin invocar a Vertex.
        if (!catalog.allows(event.entity(), event.operation(), event.eventType())) {
            store.update(state.withStatus(OperationStatus.REJECTED)
                    .withDecision("REJECTED", null, "Operación no permitida o entidad no autorizada"));
            return ProcessingOutcome.ack(OperationStatus.REJECTED, operationId,
                    "Operation not allowed or entity not authorized");
        }
        GovernorContract governorContract = governor.decide(event);
        if (governorContract.decision() == GovernorDecision.REJECTED) {
            store.update(state.withStatus(OperationStatus.REJECTED)
                    .withDecision("REJECTED", null, governorContract.reason()));
            return ProcessingOutcome.ack(OperationStatus.REJECTED, operationId, governorContract.reason());
        }
        String violation = catalog.validateGovernor(event.entity(), governorContract);
        if (violation != null) {
            store.update(state.withStatus(OperationStatus.DLQ_QUARANTINED)
                    .withDecision("APPROVED", governorContract.target_table(),
                            "Governor output violated catalog whitelist: " + violation));
            return ProcessingOutcome.ack(OperationStatus.DLQ_QUARANTINED, operationId,
                    "Governor output violated catalog whitelist");
        }
        TranslatorResult result = translator.translate(event, governorContract, hash);
        // Guard defensivo del output del Traductor: tabla/columnas/params contra la whitelist (TRANSLATION_ERROR).
        String translationError = validateTranslation(event, result);
        if (translationError != null) {
            store.update(state.withStatus(OperationStatus.TRANSLATION_ERROR)
                    .withError("Translator output violated whitelist: " + translationError));
            return ProcessingOutcome.ack(OperationStatus.TRANSLATION_ERROR, operationId,
                    "Translator output violated catalog whitelist");
        }
        PaymentRow payment = new PaymentRow(operationId, hash, governorContract.target_table(), result.parameters());
        store.commitInsert(payment, state.withStatus(OperationStatus.SUCCEEDED)
                .withDecision("APPROVED", governorContract.target_table(), "Operación validada correctamente"));
        return ProcessingOutcome.ack(OperationStatus.SUCCEEDED, operationId, "Operación validada correctamente");
    }

    private String validateTranslation(SyntheticEvent event, TranslatorResult result) {
        if (result == null) {
            return "translator returned null";
        }
        if (result.sql_template() == null || result.sql_template().isBlank()) {
            return "sql_template missing";
        }
        String target = catalog.targetTable(event.entity());
        String prefix = "INSERT INTO " + target + " ";
        if (!result.sql_template().startsWith(prefix)) {
            return "sql_template must target catalog table " + target;
        }
        if (result.parameters() == null || result.parameters().size() != 8) {
            return "exactly 8 parameters required, got " + (result.parameters() == null ? 0 : result.parameters().size());
        }
        int open = result.sql_template().indexOf('(');
        int close = result.sql_template().indexOf(')', open);
        if (open < 0 || close < 0) {
            return "sql_template malformed column list";
        }
        Set<String> allowed = new HashSet<>(catalog.fieldMapping(event.entity()).values());
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