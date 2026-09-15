package com.pagaduriasintetica.worker.contract;

// Estados normalizados de la operación en WORKER_OPERATION_STATE (VARCHAR(20)); cada uno
// define el ACK/NACK y el tratamiento de redelivery del contrato de LNB.
public enum OperationStatus {
    SUCCEEDED,
    REJECTED,
    IDEMPOTENT,
    RETRYABLE,
    DLQ_QUARANTINED,
    TRANSLATION_ERROR,
    PROCESSING
}