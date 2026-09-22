package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.JdbcExecutionResult;
import com.pagaduriasintetica.worker.contract.PreparedStatementSpec;

// Interfaz DEFINITIVA de JDBC (revisión de Carlos #4): recibe el PreparedStatementSpec del
// Traductor y devuelve un resultado interpretable (CONFIRMED / TEMPORARY_FAILURE / UNKNOWN).
// El fixture la implementa con la transacción local en la base sintética; en integración real
// será el driver Sybase. El Worker jamás le pasa texto libre: todo viene del catálogo.
public interface JdbcExecutor {

    JdbcExecutionResult execute(PreparedStatementSpec statement);
}