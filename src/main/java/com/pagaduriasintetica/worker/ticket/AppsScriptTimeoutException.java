package com.pagaduriasintetica.worker.ticket;

/**
 * Excepción lanzada cuando la consulta a Google Apps Script excede los timeouts configurados.
 * Mapea directamente a HTTP 504 GATEWAY_TIMEOUT en el controlador del WS.
 */
public class AppsScriptTimeoutException extends AppsScriptException {
    public AppsScriptTimeoutException(String message) {
        super(message);
    }

    public AppsScriptTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
