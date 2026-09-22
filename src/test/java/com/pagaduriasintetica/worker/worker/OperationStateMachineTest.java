package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

// Plan Fase 5, prueba 11: la política de transiciones rechaza saltos inválidos (p. ej.
// SUCCEEDED -> PROCESSING) y permite los válidos (PROCESSING -> JDBC_COMMITTED / REJECTED / etc.).
class OperationStateMachineTest {

    private final OperationStateMachine machine = new OperationStateMachine();

    @Test
    void permiteTransicionesValidasDeProcessing() {
        assertEquals(OperationStatus.JDBC_COMMITTED, machine.transition(OperationStatus.PROCESSING, OperationStatus.JDBC_COMMITTED));
        assertEquals(OperationStatus.REJECTED, machine.transition(OperationStatus.PROCESSING, OperationStatus.REJECTED));
        assertEquals(OperationStatus.RETRYABLE, machine.transition(OperationStatus.PROCESSING, OperationStatus.RETRYABLE));
        assertEquals(OperationStatus.IN_DOUBT, machine.transition(OperationStatus.PROCESSING, OperationStatus.IN_DOUBT));
    }

    @Test
    void permiteRedeliveryDesdeJdbcCommittedHaciaReporte() {
        assertEquals(OperationStatus.REPORT_PENDING, machine.transition(OperationStatus.JDBC_COMMITTED, OperationStatus.REPORT_PENDING));
        assertEquals(OperationStatus.RETRYABLE, machine.transition(OperationStatus.REPORT_PENDING, OperationStatus.RETRYABLE));
        assertEquals(OperationStatus.SUCCEEDED, machine.transition(OperationStatus.REPORT_PENDING, OperationStatus.SUCCEEDED));
        assertEquals(OperationStatus.SUCCEEDED, machine.transition(OperationStatus.IN_DOUBT, OperationStatus.SUCCEEDED));
    }

    @Test
    void rechazaTransicionDesdeTerminal() {
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.SUCCEEDED, OperationStatus.PROCESSING));
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.REJECTED, OperationStatus.PROCESSING));
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.DLQ_QUARANTINED, OperationStatus.SUCCEEDED));
    }

    @Test
    void rechazaSaltoNoPermitidoEnProcesamiento() {
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.PROCESSING, OperationStatus.SUCCEEDED));
    }
}