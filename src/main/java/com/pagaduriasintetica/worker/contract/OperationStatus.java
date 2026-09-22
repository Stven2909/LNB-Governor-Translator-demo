package com.pagaduriasintetica.worker.contract;

// Estados normalizados de la operación en WORKER_OPERATION_STATE (VARCHAR(20)); cada uno
// define el ACK/NACK y el tratamiento de redelivery del contrato de LNB. Incluye los estados
// del resultado idempotente (JDBC_COMMITTED, REPORT_PENDING, IN_DOUBT) definidos en la
// minuta con Alex: el ACK solo llega tras persistir evidencia (SUCCEEDED).
public enum OperationStatus {
    PROCESSING,
    JDBC_COMMITTED,
    REPORT_PENDING,
    SUCCEEDED,
    REJECTED,
    RETRYABLE,
    IN_DOUBT,
    IDEMPOTENT,
    TRANSLATION_ERROR,
    DLQ_QUARANTINED
}