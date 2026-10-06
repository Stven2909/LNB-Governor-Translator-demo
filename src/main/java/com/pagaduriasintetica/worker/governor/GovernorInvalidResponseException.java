package com.pagaduriasintetica.worker.governor;

/**
 * Respuesta inutilizable del Governor: JSON invalido, respuesta vacia o decision fuera de la
 * whitelist del catalogo. No es transitoria: reintentar daria el mismo resultado, asi que se ACK y
 * la operacion queda en cuarentena FUNCIONAL (DLQ_QUARANTINED) para revision. No se republica en
 * el topico de DLQ de Pub/Sub, que es solo para fallos tecnicos.
 */
public class GovernorInvalidResponseException extends RuntimeException {

    public GovernorInvalidResponseException(String message) {
        super(message);
    }

    public GovernorInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}