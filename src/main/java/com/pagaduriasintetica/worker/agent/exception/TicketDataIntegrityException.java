package com.pagaduriasintetica.worker.agent.exception;

/**
 * El WS de boletos reporto una inconsistencia de datos (502 DATA_INTEGRITY_ERROR).
 * No es lo mismo que un error de transporte: el dato existe pero no es confiable.
 */
public class TicketDataIntegrityException extends TicketWsException {
    public TicketDataIntegrityException(String message) {
        super(message);
    }
    public TicketDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
