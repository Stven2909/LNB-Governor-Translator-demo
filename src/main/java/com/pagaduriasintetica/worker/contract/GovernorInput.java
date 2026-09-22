package com.pagaduriasintetica.worker.contract;

// Entrada del Gobernador (revisión de Carlos #6): identificación del evento + operación real.
// No contiene el plan de escritura ni montos inventados: eso es salida del Gobernador, nunca
// entrada. workerTraceId es el trace técnico generado por el Worker (propagado a Vertex y al
// resultado); el correlationId de Alex viaja dentro del evento.
public record GovernorInput(
        PaymentCommittedEvent event,
        String workerTraceId
) {
}