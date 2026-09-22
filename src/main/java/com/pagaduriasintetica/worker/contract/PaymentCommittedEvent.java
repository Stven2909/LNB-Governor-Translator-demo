package com.pagaduriasintetica.worker.contract;

// Evento decodificado (message.data del push Pub/Sub) según el contrato oficial PAYMENT_COMMITTED
// confirmado por Alex (CONTRACT_EVENTO_V0.1). El eventVersion versiona este evento de Alex;
// contract_version/catalog_version son internos del Gobernador/Catálogo y no viven aquí.
public record PaymentCommittedEvent(
        String eventId,
        String eventType,
        String aggregateType,
        String aggregateId,
        int eventVersion,
        String destinationSystem,
        String occurredAt,
        String correlationId,
        String operationId,
        OperationData operationData
) {
}