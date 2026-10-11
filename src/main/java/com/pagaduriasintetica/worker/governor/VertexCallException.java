package com.pagaduriasintetica.worker.governor;

/**
 * Falla de TRANSPORTE del adaptador Vertex AI, clasificada por status HTTP (o -1 para IO/timeout
 * del cliente). La clasificacion de la respuesta del modelo (schema violado, JSON malformado,
 * safety block) NO se representa aqui: en ese caso VertexGovernor degrada el enriquecimiento.
 */
public class VertexCallException extends RuntimeException {

    private final int code;
    private final String status;

    public VertexCallException(int code, String status, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
    }

    public int code() {
        return code;
    }

    public String status() {
        return status;
    }

    /** 401/403/404: configuracion erronea (proyecto, IAM, endpoint) -> BLOCKED_CONFIGURATION. */
    public boolean isConfigurationFailure() {
        return code == 401 || code == 403 || code == 404;
    }

    /** 408/429/5xx/IO: transitorio o no clasificado -> enriquecimiento UNAVAILABLE (ACK). */
    public boolean isTransientUnavailable() {
        return code == 408 || code == 429 || (code >= 500 && code <= 599) || code == -1;
    }
}