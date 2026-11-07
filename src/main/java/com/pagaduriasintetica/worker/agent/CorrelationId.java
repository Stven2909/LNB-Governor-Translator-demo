package com.pagaduriasintetica.worker.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Resolucion del identificador de correlacion de una peticion.
 *
 * Se aceptan dos nombres porque el ecosistema no usa uno solo: el plan de integracion
 * de Equipo 2 documenta X-Correlation-Id, mientras que el contrato inicial del agente
 * usa X-Request-Id. Gana X-Correlation-Id cuando llegan ambos.
 *
 * El id se resuelve SIEMPRE desde el HttpServletRequest y no desde un parametro del
 * controlador: una entrada invalida (400) falla antes de ejecutar el metodo, y si el
 * advice no lo lee del request, esa rama se queda sin correlacion.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String LEGACY_HEADER = "X-Request-Id";

    /** Atributo donde el filtro deja el id resuelto para el resto de la peticion. */
    static final String ATTRIBUTE = CorrelationId.class.getName() + ".value";

    private CorrelationId() {
    }

    /** Devuelve el id recibido (X-Correlation-Id y luego X-Request-Id) o null si no vino. */
    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String value = trimToNull(request.getHeader(HEADER));
        if (value == null) {
            value = trimToNull(request.getHeader(LEGACY_HEADER));
        }
        return value;
    }

    /** Igual que resolve, pero genera uno cuando el llamador no envio ninguno. */
    public static String resolveOrGenerate(HttpServletRequest request) {
        String value = resolve(request);
        return value != null ? value : UUID.randomUUID().toString();
    }

    /**
     * Id ya resuelto por {@link CorrelationIdFilter}. Si el filtro no participo (por ejemplo,
     * una prueba con MockMvc standalone) cae al header y, si tampoco vino, genera uno.
     */
    public static String current(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(ATTRIBUTE);
        if (value instanceof String text && StringUtils.hasText(text)) {
            return text.trim();
        }
        return resolve(request);
    }

    public static String currentOrGenerate(HttpServletRequest request) {
        String value = current(request);
        return value != null ? value : UUID.randomUUID().toString();
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
