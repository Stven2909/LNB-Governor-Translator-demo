package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.JdbcExecutionResult;
import com.pagaduriasintetica.worker.contract.JdbcOutcome;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.PreparedStatementSpec;
import org.springframework.stereotype.Component;

// Implementación del fixture de JDBC (revisión de Carlos #4): la transacción local de la base
// sintética se llama executeFixtureTransaction() (INSERT en SYNTHETIC_PAYMENTS + estado
// JDBC_COMMITTED en un solo paso). La interfaz definitiva es execute(PreparedStatementSpec), y
// el resultado se interpreta: CONFIRMED -> JDBC_COMMITTED, TEMPORARY_FAILURE -> RETRYABLE,
// UNKNOWN -> IN_DOUBT. La atomicidad local del fixture NO demuestra atomicidad
// Sybase-almacenamiento del Worker (integración real por aparte).
@Component
public class FixtureJdbcExecutor implements JdbcExecutor {

    private final InMemoryStateStore store;
    private volatile JdbcOutcome forcedOutcome;
    private final java.util.concurrent.atomic.AtomicInteger executeCount =
            new java.util.concurrent.atomic.AtomicInteger();

    public FixtureJdbcExecutor(InMemoryStateStore store) {
        this.store = store;
    }

    public int executeCount() {
        return executeCount.get();
    }

    public void setForcedOutcome(JdbcOutcome outcome) {
        this.forcedOutcome = outcome;
    }

    public void resetOutcome() {
        this.forcedOutcome = null;
    }

    @Override
    public JdbcExecutionResult execute(PreparedStatementSpec statement) {
        executeCount.incrementAndGet();
        JdbcOutcome outcome = forcedOutcome;
        if (outcome == null) {
            outcome = JdbcOutcome.CONFIRMED;
        }
        return switch (outcome) {
            case TEMPORARY_FAILURE -> new JdbcExecutionResult(JdbcOutcome.TEMPORARY_FAILURE,
                    "Fixture JDBC temporary failure");
            case UNKNOWN -> {
                PaymentRow row = new PaymentRow(statement.operationId(), statement.payloadHash(),
                        statement.targetTable(), statement.parameters());
                store.commitPaymentInDoubt(row);
                yield new JdbcExecutionResult(JdbcOutcome.UNKNOWN, "Indeterminate result: commit applied, reply lost");
            }
            case CONFIRMED -> {
                PaymentRow row = new PaymentRow(statement.operationId(), statement.payloadHash(),
                        statement.targetTable(), statement.parameters());
                store.commitPayment(row);
                yield new JdbcExecutionResult(JdbcOutcome.CONFIRMED, "Fixture transaction committed");
            }
        };
    }

    /** Nombre del paso para la evidencia/demo: la transacción atómica del fixture. */
    public String executeFixtureTransactionLabel() {
        return "executeFixtureTransaction()";
    }
}