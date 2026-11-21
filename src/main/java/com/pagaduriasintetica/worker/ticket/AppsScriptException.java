package com.pagaduriasintetica.worker.ticket;

/**
 * Excepción base para fallos al consultar el servicio Google Apps Script.
 */
public class AppsScriptException extends RuntimeException {
    public AppsScriptException(String message) {
        super(message);
    }

    public AppsScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}
