package com.pagaduriasintetica.worker.contract;

// Entrada del Traductor (revisión de Carlos #6): el event del contrato (con operationData
// ORIGINAL sin transformar por el Gobernador) + el GovernorContract YA validado contra el
// catálogo + payloadHash y workerTraceId técnico. El Traductor nunca recalcula montos: la
// invariante solo se valida, y las transformaciones autorizadas salen de value_rules.
public record TranslatorInput(
        PaymentCommittedEvent event,
        GovernorContract governor,
        String payloadHash,
        String workerTraceId
) {
}