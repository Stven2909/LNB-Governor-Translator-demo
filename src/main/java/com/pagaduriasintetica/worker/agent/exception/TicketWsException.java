package com.pagaduriasintetica.worker.agent.exception;

public class TicketWsException extends RuntimeException {
    public TicketWsException(String message) {
        super(message);
    }
    public TicketWsException(String message, Throwable cause) {
        super(message, cause);
    }
}
