package com.pagaduriasintetica.worker.agent.exception;

public class InvalidWsResponseException extends RuntimeException {
    public InvalidWsResponseException(String message) {
        super(message);
    }
    public InvalidWsResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
