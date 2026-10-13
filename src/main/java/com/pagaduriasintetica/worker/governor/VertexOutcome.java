package com.pagaduriasintetica.worker.governor;

/**
 * Resultado crudo del adaptador Vertex. La causa de un resultado sin texto se transporta en
 * finishReasonName (nombre del FinishReason.Known del SDK, p. ej. SAFETY, MAX_TOKENS) para que
 * VertexGovernor clasifique sin perder informacion:
 * - safetyBlocked=true: el modelo no termino en STOP (bloqueo de seguridad, recitacion, etc.),
 *   promptFeedback con blockReason, o respuesta sin partes de texto.
 * - truncated=true: finishReason=MAX_TOKENS (respuesta cortada por limite de tokens: no es
 *   valida aunque traiga texto parcial, pero NO es un bloqueo de seguridad).
 * text es el JSON crudo del enriquecimiento (null cuando no hubo contenido utilizable).
 * La clasificacion final la hace VertexGovernor.
 */
public record VertexOutcome(boolean safetyBlocked, boolean truncated, String finishReasonName, String text) {

    public static VertexOutcome blocked(String finishReasonName) {
        return new VertexOutcome(true, false, finishReasonName, null);
    }

    public static VertexOutcome blocked() {
        return blocked(null);
    }

    public static VertexOutcome truncated(String finishReasonName) {
        return new VertexOutcome(false, true, finishReasonName, null);
    }

    public static VertexOutcome text(String text) {
        return new VertexOutcome(false, false, null, text);
    }
}