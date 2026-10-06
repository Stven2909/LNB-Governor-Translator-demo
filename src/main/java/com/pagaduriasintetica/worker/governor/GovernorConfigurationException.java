package com.pagaduriasintetica.worker.governor;

/**
 * Falla de configuracion o permisos del Governor: credencial ausente, project-id equivocado,
 * permiso denegado o modelo no habilitado. No es transitoria ni es culpa del mensaje: reintentar
 * la misma operacion solo reproduce el error. La operacion pasa a BLOCKED_CONFIGURATION con ACK
 * para evitar reintentos en loop, y queda pendiente de intervencion operativa.
 */
public class GovernorConfigurationException extends RuntimeException {

    public GovernorConfigurationException(String message) {
        super(message);
    }

    public GovernorConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}