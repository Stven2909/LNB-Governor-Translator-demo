package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

// Política de transiciones de WORKER_OPERATION_STATE: toda mutación de estado debe pasar por
// aquí (revisión de Carlos #2). Las transiciones inválidas lanzan IllegalStateException; p. ej.
// SUCCEEDED -> PROCESSING. Terminales: SUCCEEDED, REJECTED, TRANSLATION_ERROR, DLQ_QUARANTINED.
@Component
public class OperationStateMachine {

    private static final Map<OperationStatus, Set<OperationStatus>> ALLOWED = new EnumMap<>(OperationStatus.class);
    private static final Set<OperationStatus> TERMINAL = Set.of(
            OperationStatus.SUCCEEDED,
            OperationStatus.REJECTED,
            OperationStatus.TRANSLATION_ERROR,
            OperationStatus.DLQ_QUARANTINED);

    static {
        ALLOWED.put(OperationStatus.PROCESSING, Set.of(
                OperationStatus.JDBC_COMMITTED,
                OperationStatus.REJECTED,
                OperationStatus.RETRYABLE,
                OperationStatus.IN_DOUBT,
                OperationStatus.TRANSLATION_ERROR,
                OperationStatus.DLQ_QUARANTINED));
        // RETRYABLE -> REPORT_PENDING: retry de reporte cuando la fila ya existe (sin re-JDBC);
        // -> DLQ_QUARANTINED: colisión de PK con hash distinto detectada en una redelivery.
        ALLOWED.put(OperationStatus.RETRYABLE, Set.of(
                OperationStatus.PROCESSING,
                OperationStatus.REPORT_PENDING,
                OperationStatus.DLQ_QUARANTINED));
        ALLOWED.put(OperationStatus.JDBC_COMMITTED, Set.of(
                OperationStatus.REPORT_PENDING,
                OperationStatus.SUCCEEDED,
                OperationStatus.RETRYABLE,
                OperationStatus.IN_DOUBT,
                OperationStatus.DLQ_QUARANTINED));
        ALLOWED.put(OperationStatus.REPORT_PENDING, Set.of(
                OperationStatus.SUCCEEDED,
                OperationStatus.RETRYABLE,
                OperationStatus.DLQ_QUARANTINED));
        ALLOWED.put(OperationStatus.IN_DOUBT, Set.of(
                OperationStatus.SUCCEEDED,
                OperationStatus.RETRYABLE,
                OperationStatus.DLQ_QUARANTINED));
    }

    // Valida y devuelve el estado destino; lanza si la transición no está permitida.
    public OperationStatus transition(OperationStatus current, OperationStatus target) {
        if (current == null) {
            return target; // reserva inicial (PRIMER estado)
        }
        if (current == target) {
            return target; // escrituras idempotentes del mismo estado
        }
        if (TERMINAL.contains(current)) {
            throw new IllegalStateException("Invalid transition from terminal " + current + " to " + target);
        }
        Set<OperationStatus> allowed = ALLOWED.getOrDefault(current, Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException("Invalid transition " + current + " -> " + target);
        }
        return target;
    }
}