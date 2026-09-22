package com.pagaduriasintetica.worker.contract;

// Resultado del reporte del Worker hacia la API (Opción B de la minuta). El CONFLICT (409 del
// endpoint) significa "ya reportado" -> el Worker NO debe re-ejecutar JDBC (idempotencia LNB).
public record OperationResult(
        String operationId,
        String eventId,
        String correlationId,
        String workerTraceId,
        String targetTable,
        int attemptCount,
        boolean jdbcCommitted
) {
}