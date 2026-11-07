package com.pagaduriasintetica.worker.agent.exception;

/**
 * El WS de boletos respondio que la base de datos no esta disponible (503).
 * Se preserva el estado para no degradarlo a un 502 generico: el llamador necesita
 * distinguir "no puedo consultar" de "la respuesta es inconsistente".
 */
public class TicketDatabaseUnavailableException extends TicketWsException {
    public TicketDatabaseUnavailableException(String message) {
        super(message);
    }
    public TicketDatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
