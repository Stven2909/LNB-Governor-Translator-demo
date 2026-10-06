package com.pagaduriasintetica.worker.contract;

// Fila de WORKER_OPERATION_STATE: identidad = operationId (PK) + payloadHash; los with*
// construyen estados inmutables a lo largo de todo el pipeline de LNB.
//
// El estado del pipeline y el resultado del reporte son dimensiones DISTINTAS (Carlos, revision
// del Bloque 1). Razon: si el fallo del reporte se codificara en status, se perderia que el pago
// ya ocurrio en Sybase. Con reportStatus + manualActionRequired, un reporte fallido deja el estado
// en JDBC_COMMITTED y la necesidad de accion manual explicitada, sin quarantinear una operacion
// cuyo dinero ya se movio.
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
        String errorReason,
        ReportStatus reportStatus,
        boolean manualActionRequired,
        QuarantineType quarantineType
) {
    /**
     * Constructor de compatibilidad con las 10 columnas historicas: deja el reporte como no
     * aplicable y sin accion manual. Evita romper los fixtures y semillas que ya construyen el
     * registro con 10 argumentos.
     */
    public OperationState(String operationId, String eventId, String traceId, String messageId,
                          String payloadHash, OperationStatus status, int attemptCount,
                          String governorDecision, String targetTable, String errorReason) {
        this(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                governorDecision, targetTable, errorReason, ReportStatus.NOT_APPLICABLE, false, null);
    }

    public OperationState withStatus(OperationStatus s) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, s, attemptCount,
                governorDecision, targetTable, errorReason, reportStatus, manualActionRequired, quarantineType);
    }

    public OperationState withDecision(String decision, String target, String reason) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                decision, target, reason, reportStatus, manualActionRequired, quarantineType);
    }

    public OperationState withError(String reason) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                governorDecision, targetTable, reason, reportStatus, manualActionRequired, quarantineType);
    }

    public OperationState bumped() {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount + 1,
                governorDecision, targetTable, errorReason, reportStatus, manualActionRequired, quarantineType);
    }

    /** Registra el estado del reporte sin tocar el status del pipeline (el commit sigue siendo COMMITTED). */
    public OperationState withReportStatus(ReportStatus report) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                governorDecision, targetTable, errorReason, report, manualActionRequired, quarantineType);
    }

    public OperationState withManualActionRequired(boolean required) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                governorDecision, targetTable, errorReason, reportStatus, required, quarantineType);
    }

    public OperationState withQuarantineType(QuarantineType type) {
        return new OperationState(operationId, eventId, traceId, messageId, payloadHash, status, attemptCount,
                governorDecision, targetTable, errorReason, reportStatus, manualActionRequired, type);
    }
}