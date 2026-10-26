package com.pagaduriasintetica.worker.agent.exception;

public class TicketTimeoutException extends RuntimeException {
    public TicketTimeoutException(String message) {
        super(message);
    }
    public TicketTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
