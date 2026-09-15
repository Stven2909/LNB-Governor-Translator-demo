package com.pagaduriasintetica.worker.worker;

// Simula la SQLServerException de PK en WORKER_OPERATION_STATE: el begin de idempotencia de
// LNB. Duplicado o evento en vuelo se resuelven por la matriz de estados, no con check-then-act.
public class DuplicateOperationException extends RuntimeException {

    public DuplicateOperationException(String operationId) {
        super("Primary key violation on OPERATION_ID=" + operationId);
    }
}