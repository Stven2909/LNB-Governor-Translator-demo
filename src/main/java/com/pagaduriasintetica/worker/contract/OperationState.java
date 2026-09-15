package com.pagaduriasintetica.worker.contract;

// Fila de WORKER_OPERATION_STATE: identidad = operationId (PK) + payloadHash; los with*
// construyen estados inmutables a lo largo de todo el pipeline de LNB.
public record OperationState(
        String operationId,
        String eventId,
        String traceId,
        String messageId,
        String payloadHash,
        OperationStatus status,
        int attemptCount,
        String governorDecision,
        String targetTable,
        String errorReason
) {
    public OperationState withStatus(OperationStatus s) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, s, attemptCount, governorDecision, targetTable, errorReason);
    }

    public OperationState withDecision(String decision, String target, String reason) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount, decision, target, reason);
    }

    public OperationState withError(String reason) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount, governorDecision, targetTable, reason);
    }

    public OperationState bumped() {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount + 1, governorDecision, targetTable, errorReason);
    }
}