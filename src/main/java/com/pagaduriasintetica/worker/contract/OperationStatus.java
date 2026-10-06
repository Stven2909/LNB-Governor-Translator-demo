package com.pagaduriasintetica.worker.contract;

// Estados normalizados de la operación en WORKER_OPERATION_STATE (VARCHAR(32)); cada uno
// define el ACK/NACK y el tratamiento de redelivery del contrato de LNB. Incluye los estados
// del resultado idempotente (JDBC_COMMITTED, REPORT_PENDING, IN_DOUBT) definidos en la
// minuta con Alex: el ACK solo llega tras persistir evidencia (SUCCEEDED).
//
// QUARANTINE_TECHNICAL vs DLQ_QUARANTINED: no son intercambiables. DLQ_QUARANTINED es
// cuarentena FUNCIONAL (mensaje/contrato invalido: JSON malformado, hash distinto, salida
// fuera de la whitelist): se ACK para no reintentar en loop y no se republica. QUARANTINE_TECHNICAL
// es fallo TECNICO agotado (Vertex caido, reporte caido): el reintento lo hace Pub/Sub y, al
// agotar la DLQ nativa, queda en este estado terminal para operacion.
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
    DLQ_QUARANTINED,
    BLOCKED_CONFIGURATION,
    QUARANTINE_TECHNICAL
}