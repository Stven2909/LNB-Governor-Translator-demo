package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.PaymentRow;

/**
 * Contrato de persistencia de la PoC (Sybase DES real en Fase 6+; hoy InMemoryStateStore).
 * Modela las dos tablas de LNB: WORKER_OPERATION_STATE (estado/idempotencia) y
 * SYNTHETIC_PAYMENTS (pago). El contrato exige reserva vía INSERT atómico (PK operationId),
 * nunca check-then-act.
 */
public interface StateStore {

    OperationState reserve(OperationState initial) throws DuplicateOperationException;

    OperationState get(String operationId);

    PaymentRow findPayment(String operationId);

    void commitInsert(PaymentRow payment, OperationState succeeded);

    void promoteInDoubt(String operationId, OperationState succeeded);

    void update(OperationState state);

    void recordMalformed(String key, String reason);
}