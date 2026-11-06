package com.pagaduriasintetica.worker.ticket;

public class SybaseDatabaseUnavailableException extends RuntimeException {
    public SybaseDatabaseUnavailableException(String message) {
        super(message);
    }

    public SybaseDatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
