package com.pagaduriasintetica.worker.governor;

/**
 * Resultado crudo del adaptador Vertex. safetyBlocked=true cuando el modelo no termino en STOP
 * (bloqueo de seguridad, recitacion, etc.) o no devolvio partes de texto; text es el JSON crudo
 * del enriquecimiento (null/blank si no hubo contenido). La clasificacion la hace VertexGovernor.
 */
public record VertexOutcome(boolean safetyBlocked, String text) {

    public static VertexOutcome blocked() {
        return new VertexOutcome(true, null);
    }

    public static VertexOutcome text(String text) {
        return new VertexOutcome(false, text);
    }
}