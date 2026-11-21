package com.pagaduriasintetica.worker.ticket;

/**
 * Excepción lanzada cuando los datos retornados por Google Apps Script violan el contrato
 * o presentan inconsistencias de negocio (ej. premiado sin premio, monto inválido, o NOT_FOUND con premio).
 * Mapea a HTTP 502 BAD_GATEWAY (DATA_INTEGRITY_ERROR) en el controlador del WS.
 */
public class AppsScriptDataIntegrityException extends AppsScriptException {
    public AppsScriptDataIntegrityException(String message) {
        super(message);
    }

    public AppsScriptDataIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
