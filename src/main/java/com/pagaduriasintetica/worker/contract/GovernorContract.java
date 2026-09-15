package com.pagaduriasintetica.worker.contract;

import java.util.List;
import java.util.Map;

// DTO de salida del Gobernador (Vertex AI): decisión APPROVED/REJECTED + el plan de escritura
// (target_table, field_mapping) que el Worker debe validar contra el catálogo antes de seguir.
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
        List<String> violations
) {
}