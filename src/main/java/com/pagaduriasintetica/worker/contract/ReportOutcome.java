package com.pagaduriasintetica.worker.contract;

// Desenlace del ResultReporter hacia la API (Opción B). CONFLICT = 409 idempotente del
// endpoint: el Worker promueve a SUCCEEDED sin re-ejecutar JDBC.
public enum ReportOutcome {
    SUCCEEDED,
    CONFLICT,
    FAILED
}