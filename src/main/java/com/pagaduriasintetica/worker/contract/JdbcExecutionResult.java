package com.pagaduriasintetica.worker.contract;

// Resultado de la ejecución JDBC contra la interfaz definitiva. El Worker interpreta el
// outcome (CONFIRMED/TEMPORARY_FAILURE/UNKNOWN) SIN hardcodear la transición de estado.
public record JdbcExecutionResult(
        JdbcOutcome outcome,
        String detail
) {
}