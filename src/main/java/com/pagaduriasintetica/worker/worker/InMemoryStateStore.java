package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock en memoria del Sybase DES para la PoC: replica WORKER_OPERATION_STATE (control de
 * idempotencia/estado) y SYNTHETIC_PAYMENTS (tabla destino del pago).
 * putIfAbsent simula el INSERT de la PK operationId, base del diseño de LNB contra dobles
 * envíos de Pub/Sub (redelivery); commitInsert es la transacción JDBC atómica; promoteInDoubt
 * y recordMalformed cubren la recuperación in-doubt y la cuarentena (DLQ) del contrato.
 */
@Component
public class InMemoryStateStore implements StateStore {

    private final ConcurrentMap<String, OperationState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PaymentRow> payments = new ConcurrentHashMap<>();
    private final List<String> quarantineAudit = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public OperationState reserve(OperationState initial) throws DuplicateOperationException {
        // Reserva atómica = INSERT de la PK; si ya existe, es duplicado o evento en vuelo.
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
    public synchronized void commitInsert(PaymentRow payment, OperationState succeeded) {
        // Transacción JDBC simulada: inserta fila y marca SUCCEEDED en un solo paso atómico.
        if (payments.containsKey(payment.operationId())) {
            throw new DuplicateOperationException(payment.operationId());
        }
        payments.put(payment.operationId(), payment);
        OperationState current = states.get(payment.operationId());
        OperationState next = (current != null ? current : succeeded)
                .withStatus(OperationStatus.SUCCEEDED)
                .withDecision("APPROVED", payment.targetTable(), "Operación validada correctamente");
        states.put(payment.operationId(), next);
    }

    @Override
    public synchronized void promoteInDoubt(String operationId, OperationState succeeded) {
        // Recuperación in-doubt: el commit ya se aplicó antes de un crash, solo se normaliza el estado.
        OperationState current = states.get(operationId);
        OperationState next = (current != null ? current : succeeded)
                .withStatus(OperationStatus.SUCCEEDED)
                .withDecision("APPROVED", succeeded.targetTable(), "Promoted after in-doubt recovery (commit already applied)");
        states.put(operationId, next);
    }

    @Override
    public void update(OperationState state) {
        states.put(state.operationId(), state);
    }

    @Override
    public void recordMalformed(String key, String reason) {
        quarantineAudit.add("key=" + key + " reason=" + reason);
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
        states.put(operationId, new OperationState(operationId, "evt-doubt", "trace-doubt", "msg-doubt",
                stateHash, OperationStatus.PROCESSING, 1, null, null, null));
        payments.put(operationId, new PaymentRow(operationId, rowHash, targetTable, parameters));
    }
}