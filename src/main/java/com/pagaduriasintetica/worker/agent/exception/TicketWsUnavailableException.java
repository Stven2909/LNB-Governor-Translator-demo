package com.pagaduriasintetica.worker.agent.exception;

/**
 * El WS de boletos respondio 503, pero no declaro {@code DATABASE_UNAVAILABLE}.
 * Se preserva el estado 503 para no degradarlo a 502, pero no se afirma una causa
 * que el WS no documento (por ejemplo, un 503 de un proxy intermedio).
 */
public class TicketWsUnavailableException extends TicketWsException {
    public TicketWsUnavailableException(String message) {
        super(message);
    }
    public TicketWsUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
