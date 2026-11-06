package com.pagaduriasintetica.worker.ticket;

/**
 * Excepción lanzada cuando los datos leídos de Sybase violan las reglas de integridad de negocio
 * (por ejemplo, premio nulo en boleto premiado, o monto con escala decimal no autorizada).
 * Debe mapearse a HTTP 502 BAD_GATEWAY (DATA_INTEGRITY_ERROR), diferenciándose de un fallo de conexión (503).
 */
public class SybaseDataIntegrityException extends RuntimeException {

    public SybaseDataIntegrityException(String message) {
        super(message);
    }

    public SybaseDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
