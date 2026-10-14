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

    /**
     * Veto restrictivo de Vertex (Q1 JD): la traducción altera el sentido de la operación
     * confirmada. El contrato es REJECTED con el motivo de Vertex y el enriquecimiento
     * adjunto (contexto para revisión con operationId/correlationId del evento); el plan
     * queda en null porque no hay escritura que validar. El pago confirmado NO se revierte:
     * el Worker persiste REJECTED con ACK y jamás ejecuta JDBC. Un veto nunca puede
     * convertirse en APPROVED: este método solo produce REJECTED.
     */
    public GovernorContract reject(GovernorInput input,
                                   String reason,
                                   List<String> violations,
                                   GovernorEnrichment enrichment) {
        return new GovernorContract(
                CONTRACT_VERSION,
                GovernorDecision.REJECTED,
                input.event().operationId(),
                input.workerTraceId(),
                reason,
                null,
                null,
                null,
                Map.of(),
                Catalog.CATALOG_VERSION,
                violations != null ? List.copyOf(violations) : List.of(),
                GovernorEnrichmentStatus.ATTACHED,
                enrichment
        );
    }
}