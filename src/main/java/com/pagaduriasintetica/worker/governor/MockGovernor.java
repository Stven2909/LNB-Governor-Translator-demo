package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Gobernador mock (en producción de LNB sería la respuesta de Vertex AI con un LLM).
 * Por defecto aprueba devolviendo el plan exacto del catálogo; setOverride() inyecta
 * comportamientos para probar ramas: alucinación (Caso 4 -> DLQ) y rechazo de negocio
 * (paso 9 -> REJECTED).
 */
@Component
public class MockGovernor implements Governor {

    private final Catalog catalog;
    private volatile Function<SyntheticEvent, GovernorContract> override;

    public MockGovernor(Catalog catalog) {
        this.catalog = catalog;
    }

    public void setOverride(Function<SyntheticEvent, GovernorContract> behavior) {
        this.override = behavior;
    }

    public void resetOverride() {
        this.override = null;
    }

    @Override
    public GovernorContract decide(SyntheticEvent event) {
        Function<SyntheticEvent, GovernorContract> behavior = override;
        if (behavior != null) {
            return behavior.apply(event);
        }
        String entity = event.entity();
        Map<String, String> fieldMapping = new LinkedHashMap<>(catalog.fieldMapping(entity));
        return new GovernorContract(
                event.contract_version(),
                GovernorDecision.APPROVED,
                event.operationId(),
                event.traceId(),
                "Operación validada correctamente",
                catalog.targetTable(entity),
                catalog.requiredFields(entity),
                fieldMapping,
                Map.of(),
                Catalog.CATALOG_VERSION,
                List.of()
        );
    }
}