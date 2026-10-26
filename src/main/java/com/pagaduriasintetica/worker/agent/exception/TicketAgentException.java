package com.pagaduriasintetica.worker.agent.exception;

/**
 * Excepción para respuesta inválida del agente.
 * Se lanza cuando el modelo no llama a la herramienta o altera los parámetros.
 */
public class TicketAgentException extends RuntimeException {
    public TicketAgentException(String message) {
        super(message);
    }
    public TicketAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
