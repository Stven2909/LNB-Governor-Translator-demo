package com.pagaduriasintetica.worker.contract;

import java.util.List;
import java.util.Map;

// DTO de salida del Gobernador (Vertex AI): decisión APPROVED/REJECTED + el plan de escritura
// (target_table, field_mapping) que el Worker debe validar contra el catálogo antes de seguir.
// Los campos enrichmentStatus/enrichment son ADITIVOS (opcionales) dentro de V1.1: nunca alteran
// la decisión, el plan ni el SQL; solo transportan el enriquecimiento no autoritativo de Vertex.
public record GovernorContract(
        String contract_version,
        GovernorDecision decision,
        String operationId,
        String traceId,
        String reason,
        String target_table,
        List<String> required_fields,
        Map<String, String> field_mapping,
        Map<String, Object> value_rules,
        String catalog_version,
        List<String> violations,
        GovernorEnrichmentStatus enrichmentStatus,
        GovernorEnrichment enrichment
) {

    // Constructor de compatibilidad (11 argumentos previos, antes del enriquecimiento aditivo):
    // produce un contrato sin enriquecimiento (DISABLED).
    public GovernorContract(
            String contract_version,
            GovernorDecision decision,
            String operationId,
            String traceId,
            String reason,
            String target_table,
            List<String> required_fields,
            Map<String, String> field_mapping,
            Map<String, Object> value_rules,
            String catalog_version,
            List<String> violations) {
        this(contract_version, decision, operationId, traceId, reason, target_table,
                required_fields, field_mapping, value_rules, catalog_version, violations,
                GovernorEnrichmentStatus.DISABLED, null);
    }
}