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
            OperationStatus.DLQ_QUARANTINED,
            OperationStatus.BLOCKED_CONFIGURATION,
            OperationStatus.QUARANTINE_TECHNICAL);

    static {
        ALLOWED.put(OperationStatus.PROCESSING, Set.of(
                OperationStatus.JDBC_COMMITTED,
                OperationStatus.REJECTED,
                OperationStatus.RETRYABLE,
                OperationStatus.IN_DOUBT,
                OperationStatus.TRANSLATION_ERROR,
                OperationStatus.DLQ_QUARANTINED,
                OperationStatus.BLOCKED_CONFIGURATION,
                OperationStatus.QUARANTINE_TECHNICAL));
        // RETRYABLE -> REPORT_PENDING: retry de reporte cuando la fila ya existe (sin re-JDBC);
        // -> DLQ_QUARANTINED: colisión de PK con hash distinto detectada en una redelivery;
        // -> BLOCKED_CONFIGURATION/QUARANTINE_TECHNICAL: el reintento falló por config o por
        // indisponibilidad del servicio, no por el mensaje.
        ALLOWED.put(OperationStatus.RETRYABLE, Set.of(
                OperationStatus.PROCESSING,
                OperationStatus.REPORT_PENDING,
                OperationStatus.DLQ_QUARANTINED,
                OperationStatus.BLOCKED_CONFIGURATION,
                OperationStatus.QUARANTINE_TECHNICAL));
        // Principio LNB "commit significa exito" (Carlos, revision del Bloque 1): una vez que
        // Sybase confirma, el pago ya ocurrio. Un problema posterior del REPORTE o de la
        // configuracion NO puede convertir la operacion entera en BLOCKED_CONFIGURATION ni en
        // QUARANTINE_TECHNICAL: eso declararia fallida una operacion cuyo dinero ya se movio y
        // dejaria el pago sin Registrar. Esos dos estados son alcanzables SOLO antes del commit.
        // El fallo de reporte post-commit se registra en reportStatus=BLOCKED +
        // manualActionRequired=true conservando status JDBC_COMMITTED / REPORT_PENDING.
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