package com.pagaduriasintetica.worker.contract;

import java.util.List;
import java.util.Map;

/**
 * Enriquecimiento NO autoritativo de Vertex: se adjunta al GovernorContract tal cual, no se usa
 * para decidir ni para construir el plan/SQL. Cuando Vertex no entrego enrichment valido, solo
 * se transporta el motivo (unavailableReason) para que el estado degradado sea visible y
 * distinguible en operacion.
 */
public record GovernorEnrichment(
        String unavailableReason,
        String summary,
        List<String> observations,
        Map<String, String> suggestedMapping
) {

    public static GovernorEnrichment attached(String summary, List<String> observations,
                                              Map<String, String> suggestedMapping) {
        return new GovernorEnrichment(null, summary, observations, suggestedMapping);
    }

    public static GovernorEnrichment unavailable(String reason) {
        return new GovernorEnrichment(reason, null, List.of(), Map.of());
    }

    public boolean attached() {
        return summary != null;
    }
}