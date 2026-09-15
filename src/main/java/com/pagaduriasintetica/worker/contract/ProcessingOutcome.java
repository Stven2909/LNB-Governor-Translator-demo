package com.pagaduriasintetica.worker.contract;

// Resultado del pipeline hacia Pub/Sub: ACK (HTTP 200) solo cuando la evidencia ya está
// persistida (regla de LNB: no perder duplicados); NACK (500) fuerza redelivery en vuelo/retry.
public record ProcessingOutcome(
        boolean ack,
        OperationStatus status,
        String operationId,
        String reason
) {
    public static ProcessingOutcome ack(OperationStatus status, String operationId, String reason) {
        return new ProcessingOutcome(true, status, operationId, reason);
    }

    public static ProcessingOutcome nack(OperationStatus status, String operationId, String reason) {
        return new ProcessingOutcome(false, status, operationId, reason);
    }
}