package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationResult;
import com.pagaduriasintetica.worker.contract.ReportResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

// Implementación mock del ResultReporter para el PoC: devuelve SUCCEEDED por defecto.
// setOverride() inyecta FAILED (fallo temporal del reporte) y CONFLICT (409 idempotente del
// endpoint) para las pruebas de recuperación sin re-ejecutar JDBC.
// reportCount() cuenta las llamadas reales: permite afirmar que una entrega concurrente no
// duplica el reporte (no solo que el estado final sea SUCCEEDED).
@Component
public class MockResultReporter implements ResultReporter {

    private volatile Function<OperationResult, ReportResult> override;
    private final AtomicInteger reportCount = new AtomicInteger();

    public void setOverride(Function<OperationResult, ReportResult> behavior) {
        this.override = behavior;
    }

    public void resetOverride() {
        this.override = null;
    }

    /** Reinicia comportamiento y contador entre casos. */
    public void reset() {
        this.override = null;
        this.reportCount.set(0);
    }

    public int reportCount() {
        return reportCount.get();
    }

    @Override
    public ReportResult report(OperationResult result) {
        reportCount.incrementAndGet();
        Function<OperationResult, ReportResult> behavior = override;
        if (behavior != null) {
            return behavior.apply(result);
        }
        return ReportResult.ok("reporte simulado OK (Opción B; HTTP real pendiente de la API)");
    }
}