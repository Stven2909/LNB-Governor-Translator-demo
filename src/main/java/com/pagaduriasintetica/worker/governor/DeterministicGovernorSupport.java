package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorEnrichment;
import com.pagaduriasintetica.worker.contract.GovernorEnrichmentStatus;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan determinístico APPROVED construido EXCLUSIVAMENTE desde el catálogo congelado. Es la
 * autoridad de decisión/plan/SQL: cualquier enriquecimiento de Vertex se ADJUNTA después (campos
 * aditivos del GovernorContract) y nunca modifica estos campos. Sin credenciales, sin JDBC, sin
 * I/O. Reutilizado por MockGovernor (PoC) y por VertexGovernor (E3.2) para que ambas ramas
 * produzcan exactamente el mismo plan.
 */
@Component
public class DeterministicGovernorSupport {

    public static final String CONTRACT_VERSION = "CONTRACT_PAYMENT_COMMITTED_V0.1";

    private final Catalog catalog;

    public DeterministicGovernorSupport(Catalog catalog) {
        this.catalog = catalog;
    }

    public String catalogVersion() {
        return Catalog.CATALOG_VERSION;
    }

    public String targetTable(String aggregateType) {
        return catalog.targetTable(aggregateType);
    }

    public GovernorContract approve(GovernorInput input,
                                    GovernorEnrichmentStatus enrichmentStatus,
                                    GovernorEnrichment enrichment) {
        String aggregateType = input.event().aggregateType();
        Map<String, String> fieldMapping = new LinkedHashMap<>(catalog.fieldMapping(aggregateType));
        Map<String, Object> valueRules = new LinkedHashMap<>();
        valueRules.putAll(catalog.valueRules(aggregateType));
        return new GovernorContract(
                CONTRACT_VERSION,
                GovernorDecision.APPROVED,
                input.event().operationId(),
                input.workerTraceId(),
                "Operación validada correctamente",
                catalog.targetTable(aggregateType),
                catalog.requiredFields(aggregateType),
                fieldMapping,
                valueRules,
                Catalog.CATALOG_VERSION,
                List.of(),
                enrichmentStatus,
                enrichment
        );
    }
}