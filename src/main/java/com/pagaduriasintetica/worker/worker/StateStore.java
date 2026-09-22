package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.PaymentRow;

/**
 * Contrato de persistencia de la PoC (Sybase DES real en integración; hoy InMemoryStateStore).
 * Modela WORKER_OPERATION_STATE (estado/idempotencia) y SYNTHETIC_PAYMENTS (pago del fixture).
 * Las operaciones de alto nivel respetan OperationStateMachine: toda mutación de estado valida
 * la transición, prohibiendo p. ej. SUCCEEDED -> PROCESSING.
 */
public interface StateStore {

    /** Reserva atómica (INSERT de la PK operationId): duplicado o evento en vuelo lanzan DuplicateOperationException. */
    OperationState reserveAtomic(OperationState initial) throws DuplicateOperationException;

    OperationState get(String operationId);

    PaymentRow findPayment(String operationId);

    /** PROCESING -> REJECTED (regla de negocio/Gobernador). */
    void markRejected(String operationId, String reason);

    /** RETRYABLE -> PROCESSING: reclama la operación para un nuevo intento (bump de intento). */
    OperationState markProcessing(String operationId);

    /** -> RETRYABLE (fallo temporal de JDBC o de reporte; redelivery programada). */
    void markRetryable(String operationId, String reason);

    /** -> IN_DOUBT (resultado JDBC indeterminado; se concilia, no se reejecuta JDBC). */
    void markInDoubt(String operationId, String reason);

    /** JDBC_COMMITTED -> REPORT_PENDING (reporte en curso hacia la API). */
    void markReportPending(String operationId);

    /** -> SUCCEEDED (evidencia persistida; ACK). */
    void markSucceeded(String operationId);

    /** -> DLQ_QUARANTINED (alucinación, colisión de PK, inconsistencia). */
    void markQuarantined(String operationId, String reason);

    /** -> TRANSLATION_ERROR (plan del Traductor fuera de la whitelist). */
    void markTranslationError(String operationId, String reason);

    void update(OperationState state);

    void recordMalformed(String key, String reason);
}