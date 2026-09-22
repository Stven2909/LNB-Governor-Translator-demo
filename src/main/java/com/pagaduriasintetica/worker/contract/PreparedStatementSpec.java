package com.pagaduriasintetica.worker.contract;

// Especificación de un PreparedStatement preparado por el Traductor: solo tabla/columnas y
// parámetros del catálogo. Incluye la identidad (operationId/payloadHash) que el fixture
// necesita para persistir la evidencia de SYNTHETIC_PAYMENTS. Es la entrada de la interfaz
// definitiva de JDBC (JdbcExecutionResult execute(PreparedStatementSpec)).
public record PreparedStatementSpec(
        String sql_template,
        String targetTable,
        java.util.List<Object> parameters,
        String operationId,
        String payloadHash
) {
}