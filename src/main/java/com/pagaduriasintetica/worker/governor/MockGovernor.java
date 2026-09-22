package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Gobernador mock (en producción de LNB sería la respuesta de Vertex AI con un LLM y su
 * responseSchema). Por defecto aprueba devolviendo el plan EXACTO del catálogo para el
 * aggregateType + eventType; setOverride() inyecta comportamientos para probar ramas:
 * alucinación (tabla fuera de la whitelist -> DLQ) y rechazo de negocio (paso 9 -> REJECTED).
 * El GovernorContract conserva contract_version y catalog_version (el eventVersion de Alex no
 * sustituye a esos dos campos internos).
 */
@Component
public class MockGovernor implements Governor {

    private final Catalog catalog;
    private volatile Function<GovernorInput, GovernorContract> override;

    public MockGovernor(Catalog catalog) {
        this.catalog = catalog;
    }

    public void setOverride(Function<GovernorInput, GovernorContract> behavior) {
        this.override = behavior;
    }

    public void resetOverride() {
        this.override = null;
    }

    @Override
    public GovernorContract decide(GovernorInput input) {
        Function<GovernorInput, GovernorContract> behavior = override;
        if (behavior != null) {
            return behavior.apply(input);
        }
        String aggregateType = input.event().aggregateType();
        Map<String, String> fieldMapping = new LinkedHashMap<>(catalog.fieldMapping(aggregateType));
        Map<String, Object> valueRules = new LinkedHashMap<>();
        valueRules.putAll(catalog.valueRules(aggregateType));
        return new GovernorContract(
                PayloadHasherContractVersion(),
                GovernorDecision.APPROVED,
                input.event().operationId(),
                input.workerTraceId(),
                "Operación validada correctamente",
                catalog.targetTable(aggregateType),
                catalog.requiredFields(aggregateType),
                fieldMapping,
                valueRules,
                Catalog.CATALOG_VERSION,
                List.of()
        );
    }

    private String PayloadHasherContractVersion() {
        return "CONTRACT_PAYMENT_COMMITTED_V0.1";
    }
}