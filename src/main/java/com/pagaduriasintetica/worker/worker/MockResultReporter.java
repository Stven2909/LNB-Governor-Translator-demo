package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationResult;
import com.pagaduriasintetica.worker.contract.ReportResult;
import org.springframework.stereotype.Component;

import java.util.function.Function;

// Implementación mock del ResultReporter para el PoC: devuelve SUCCEEDED por defecto.
// setOverride() inyecta FAILED (fallo temporal del reporte) y CONFLICT (409 idempotente del
// endpoint) para las pruebas de recuperación sin re-ejecutar JDBC.
@Component
public class MockResultReporter implements ResultReporter {

    private volatile Function<OperationResult, ReportResult> override;

    public void setOverride(Function<OperationResult, ReportResult> behavior) {
        this.override = behavior;
    }

    public void resetOverride() {
        this.override = null;
    }

    @Override
    public ReportResult report(OperationResult result) {
        Function<OperationResult, ReportResult> behavior = override;
        if (behavior != null) {
            return behavior.apply(result);
        }
        return ReportResult.ok("reporte simulado OK (Opción B; HTTP real pendiente de la API)");
    }
}