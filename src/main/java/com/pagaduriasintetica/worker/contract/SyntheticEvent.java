package com.pagaduriasintetica.worker.contract;

// Evento decodificado (message.data del push Pub/Sub): los datos de negocio que llegan al
// Worker de la Pagaduría. type SYNTHETIC_PAYMENT_REQUESTED, operation INSERT,
// entity synthetic_payment en el contrato sintético.
public record SyntheticEvent(
        String contract_version,
        String eventId,
        String eventType,
        String operationId,
        String traceId,
        String occurredAt,
        String operation,
        String entity,
        SyntheticPayload payload
) {
}