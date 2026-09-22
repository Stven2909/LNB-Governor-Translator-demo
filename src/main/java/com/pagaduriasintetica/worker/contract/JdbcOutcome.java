package com.pagaduriasintetica.worker.contract;

// Interpretación del resultado JDBC por el Worker (revisión de Carlos #4): el Worker NO
// hardcodea la transición, interpreta la resultado devuelto por la interfaz definitiva
// JdbcExecutionResult execute(PreparedStatementSpec).
public enum JdbcOutcome {
    CONFIRMED,         // -> JDBC_COMMITTED
    TEMPORARY_FAILURE, // -> RETRYABLE (no se reejecuta hasta nueva entrega)
    UNKNOWN            // -> IN_DOUBT (indeterminado; se concilia, no se reejecuta JDBC)
}