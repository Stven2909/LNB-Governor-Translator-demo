package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock en memoria del almacenamiento para la PoC: replica WORKER_OPERATION_STATE (control de
 * idempotencia/estado) y SYNTHETIC_PAYMENTS (tabla destino del pago del fixture).
 * reserveAtomic usa putIfAbsent = INSERT de la PK operationId (base del diseño de LNB contra
 * dobles envíos de Pub/Sub). TODAS las mutaciones de estado pasan por OperationStateMachine:
 * una transición inválida (p. ej. SUCCEEDED -> PROCESSING) lanza IllegalStateException.
 * NOTA de atomicidad: fixture y estado viven en la misma "base sintética", así que el INSERT+
 * UPDATE es atómico local; esto NO demuestra atomicidad Sybase-Worker en la integración real.
 */
@Component
public class InMemoryStateStore implements StateStore {

    private final ConcurrentMap<String, OperationState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PaymentRow> payments = new ConcurrentHashMap<>();
    private final List<String> quarantineAudit = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final OperationStateMachine machine;

    public InMemoryStateStore(OperationStateMachine machine) {
        this.machine = machine;
    }

    @Override
    public OperationState reserveAtomic(OperationState initial) throws DuplicateOperationException {
        OperationState existing = states.putIfAbsent(initial.operationId(), initial);
        if (existing != null) {
            throw new DuplicateOperationException(initial.operationId());
        }
        return initial;
    }

    @Override
    public OperationState get(String operationId) {
        return states.get(operationId);
    }

    @Override
    public PaymentRow findPayment(String operationId) {
        return payments.get(operationId);
    }

    @Override
    public void markRejected(String operationId, String reason) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.REJECTED))
                .withError(reason);
        states.put(operationId, next);
    }

    @Override
    public OperationState markProcessing(String operationId) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.PROCESSING))
                .bumped();
        states.put(operationId, next);
        return next;
    }

    @Override
    public void markRetryable(String operationId, String reason) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.RETRYABLE))
                .withError(reason);
        states.put(operationId, next);
    }

    @Override
    public void markInDoubt(String operationId, String reason) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.IN_DOUBT))
                .withError(reason);
        states.put(operationId, next);
    }

    @Override
    public void markReportPending(String operationId) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.REPORT_PENDING));
        states.put(operationId, next);
    }

    @Override
    public void markSucceeded(String operationId) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.SUCCEEDED))
                .withDecision("APPROVED", current.targetTable() == null ? "-" : current.targetTable(),
                        "Operación validada correctamente");
        states.put(operationId, next);
    }

    @Override
    public void markQuarantined(String operationId, String reason) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.DLQ_QUARANTINED))
                .withError(reason);
        states.put(operationId, next);
    }

    @Override
    public void markTranslationError(String operationId, String reason) {
        OperationState current = require(operationId);
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.TRANSLATION_ERROR))
                .withError(reason);
        states.put(operationId, next);
    }

    @Override
    public void update(OperationState state) {
        OperationState current = states.get(state.operationId());
        machine.transition(current == null ? null : current.status(), state.status());
        states.put(state.operationId(), state);
    }

    @Override
    public void recordMalformed(String key, String reason) {
        quarantineAudit.add("key=" + key + " reason=" + reason);
    }

    /**
     * Transacción del fixture (usada por FixtureJdbcExecutor): INSERT en SYNTHETIC_PAYMENTS y
     * paso a JDBC_COMMITTED en un solo paso atómico de la base sintética.
     */
    public synchronized void commitPayment(PaymentRow payment) {
        if (payments.containsKey(payment.operationId())) {
            throw new DuplicateOperationException(payment.operationId());
        }
        payments.put(payment.operationId(), payment);
        OperationState current = require(payment.operationId());
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.JDBC_COMMITTED))
                .withDecision("APPROVED", payment.targetTable(), "Operación validada correctamente");
        states.put(payment.operationId(), next);
    }

    /** Fixture: resultado indeterminado (UNKNOWN) -> fila persistida + estado IN_DOUBT. */
    public synchronized void commitPaymentInDoubt(PaymentRow payment) {
        if (payments.containsKey(payment.operationId())) {
            throw new DuplicateOperationException(payment.operationId());
        }
        payments.put(payment.operationId(), payment);
        OperationState current = require(payment.operationId());
        OperationState next = current.withStatus(machine.transition(current.status(), OperationStatus.IN_DOUBT))
                .withError("Indeterminate JDBC result (commit posiblemente aplicado)");
        states.put(payment.operationId(), next);
    }

    public int paymentCount(String operationId) {
        return payments.containsKey(operationId) ? 1 : 0;
    }

    public List<String> quarantineAudit() {
        return List.copyOf(quarantineAudit);
    }

    public void reset() {
        states.clear();
        payments.clear();
        quarantineAudit.clear();
    }

    public synchronized void seedDoubtful(String operationId, String stateHash, String rowHash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-doubt", "wtr-doubt", "msg-doubt",
                stateHash, OperationStatus.PROCESSING, 1, null, null, null));
        payments.put(operationId, new PaymentRow(operationId, rowHash, targetTable, parameters));
    }

    /** Fixture IN_DOUBT = fila persistida + estado indeterminado (para conciliar por PAYLOAD_HASH).
     *  rowHash distinto de stateHash simula la inconsistencia que termina en DLQ. */
    public synchronized void seedInDoubt(String operationId, String stateHash, String rowHash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-in-doubt", "wtr-in-doubt", "msg-in-doubt",
                stateHash, OperationStatus.IN_DOUBT, 1, null, null, "Indeterminate JDBC result (commit posiblemente aplicado)"));
        payments.put(operationId, new PaymentRow(operationId, rowHash, targetTable, parameters));
    }

    /**
     * Fixture: estado JDBC_COMMITTED con su fila de pago ya persistida (crash tras el commit,
     * antes del reporte). La redelivery SÓLO debe reintentar el reporte, nunca re-ejecutar JDBC.
     */
    public synchronized void seedJdbcCommitted(String operationId, String hash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-jdbc", "wtr-jdbc", "msg-jdbc",
                hash, OperationStatus.JDBC_COMMITTED, 1, "APPROVED", targetTable, null));
        payments.put(operationId, new PaymentRow(operationId, hash, targetTable, parameters));
    }

    private OperationState require(String operationId) {
        OperationState current = states.get(operationId);
        if (current == null) {
            throw new IllegalStateException("No OperationState reserved for " + operationId);
        }
        return current;
    }
}