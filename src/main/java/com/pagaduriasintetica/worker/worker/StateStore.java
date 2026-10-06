package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.PaymentRow;

/**
 * Contrato de persistencia de la PoC (Sybase DES real en integración; hoy InMemoryStateStore).
 * Modela WORKER_OPERATION_STATE (estado/idempotencia) y SYNTHETIC_PAYMENTS (pago del fixture).
 * Las operaciones de alto nivel respetan OperationStateMachine: toda mutación de estado valida
 * la transición, prohibiendo p. ej. SUCCEEDED -> PROCESSING.
 */
public interface StateStore {

    /** Reserva atómica (INSERT de la PK operationId): duplicado o evento en vuelo lanzan DuplicateOperationException. */
    OperationState reserveAtomic(OperationState initial) throws DuplicateOperationException;

    OperationState get(String operationId);

    PaymentRow findPayment(String operationId);

    /** PROCESING -> REJECTED (regla de negocio/Gobernador). */
    void markRejected(String operationId, String reason);

    /** RETRYABLE -> PROCESSING: reclama la operación para un nuevo intento (bump de intento). */
    OperationState markProcessing(String operationId);

    /** -> RETRYABLE (fallo temporal de JDBC o de reporte; redelivery programada). */
    void markRetryable(String operationId, String reason);

    /** -> IN_DOUBT (resultado JDBC indeterminado; se concilia, no se reejecuta JDBC). */
    void markInDoubt(String operationId, String reason);

    /** JDBC_COMMITTED -> REPORT_PENDING (reporte en curso hacia la API). */
    void markReportPending(String operationId);

    /** -> SUCCEEDED (evidencia persistida; ACK). */
    void markSucceeded(String operationId);

    /** -> DLQ_QUARANTINED (alucinación, colisión de PK, inconsistencia). */
    void markQuarantined(String operationId, String reason);

    /** -> TRANSLATION_ERROR (plan del Traductor fuera de la whitelist). */
    void markTranslationError(String operationId, String reason);

    /** -> BLOCKED_CONFIGURATION (config o permisos del Governor; ACK, requiere accion operativa). */
    void markBlockedConfiguration(String operationId, String reason);

    /** -> QUARANTINE_TECHNICAL (fallo tecnico agotado ANTES del commit; la DLQ nativa de Pub/Sub ya lo recibio). */
    void markQuarantineTechnical(String operationId, String reason);

    /**
     * Fallo del REPORTE con el commit ya confirmado. No cambia el status del pipeline: la operacion
     * sigue en JDBC_COMMITTED / REPORT_PENDING porque el pago en Sybase ocurrio. Se registra
     * reportStatus=BLOCKED + manualActionRequired=true para que la accion sea visible y trazable.
     * La redelivery posterior reintenta SOLO el reporte, nunca el JDBC.
     */
    void markReportBlocked(String operationId, String reason);

    /**
     * Fallo TRANSITORIO del reporte con el commit ya confirmado: el estado sigue en
     * JDBC_COMMITTED / REPORT_PENDING (el pago ocurrio) y el reporte queda PENDING para el proximo
     * intento. No se degrada el status a RETRYABLE, para no perder la verdad de que Sybase ya
     * confirmo. La redelivery reintenta SOLO el reporte.
     */
    void markReportRetryable(String operationId, String reason);

    /**
     * Lease con expiracion: indica que la operacion esta en vuelo y hasta cuando. Sin el, una
     * operacion que quedo en PROCESSING por un cierre de instancia bloquearia la redelivery de
     * forma indefinida. Un lease vigente significa "otro hilo o instancia esta trabajando en
     * esto" (no se roba); vencido o ausente significa "el intento anterior murio" (se reclama).
     */
    boolean leaseIsHeldAndFresh(String operationId);

    /** Libera el lease: la operacion dejo de estar en vuelo. */
    void releaseLease(String operationId);

    /** Instante de expiracion del lease vigente, o null si no hay lease. */
    java.time.Instant leaseExpiry(String operationId);

    void update(OperationState state);

    void recordMalformed(String key, String reason);
}