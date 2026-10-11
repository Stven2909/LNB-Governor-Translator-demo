package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorEnrichmentStatus;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * Gobernador determinístico (mock) de la PoC: devuelve el plan EXACTO del catálogo para el
 * aggregateType + eventType, sin enriquecimiento (DISABLED). setOverride() inyecta
 * comportamientos para probar ramas: alucinación (tabla fuera de la whitelist -> DLQ) y
 * rechazo de negocio (paso 9 -> REJECTED). El GovernorContract conserva contract_version y
 * catalog_version (el eventVersion de Alex no sustituye a esos dos campos internos).
 * Activado por defecto (app.governor.mode=MOCK); en VERTEX toma el control VertexGovernor.
 */
@Component
@ConditionalOnProperty(name = "app.governor.mode", havingValue = "MOCK", matchIfMissing = true)
public class MockGovernor implements Governor {

    private final DeterministicGovernorSupport support;
    private volatile Function<GovernorInput, GovernorContract> override;

    @Autowired
    public MockGovernor(Catalog catalog) {
        this(new DeterministicGovernorSupport(catalog));
    }

    public MockGovernor(DeterministicGovernorSupport support) {
        this.support = support;
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
        return support.approve(input, GovernorEnrichmentStatus.DISABLED, null);
    }
}