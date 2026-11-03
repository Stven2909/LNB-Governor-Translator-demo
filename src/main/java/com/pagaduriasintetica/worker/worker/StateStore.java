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

    /**
     * Resultado de un reclamo atomico. El reclamo existe porque decidir con una foto del estado y
     * escribir despues es una carrera: entre la lectura y la escritura otra entrega puede completar
     * la operacion, y la escritura atrasada intentaria una transicion ilegal (p. ej.
     * SUCCEEDED -> REPORT_PENDING). El reclamo hace la decision y la transicion en una sola
     * operacion sobre la clave.
     */
    enum ClaimResult {
        /** Esta entrega es la duena del paso. */
        CLAIMED,
        /** Otra entrega ya completo la operacion: ACK idempotente, sin repetir el trabajo. */
        ALREADY_SUCCEEDED,
        /** Otro dueno tiene un lease vigente: no se roba el trabajo, NACK. */
        IN_FLIGHT,
        /** El estado vigente no admite este paso: no se toca, la entrega se devuelve. */
        NOT_CLAIMABLE
    }

    /**
     * Reclamo del paso de REPORTE (JDBC_COMMITTED / REPORT_PENDING / RETRYABLE con fila ya
     * persistida). Solo la entrega que obtiene CLAIMED transiciona a REPORT_PENDING y reporta.
     * Con un lease vigente devuelve IN_FLIGHT para que no haya reporte duplicado; si la operacion
     * ya termino devuelve ALREADY_SUCCEEDED en vez de lanzar una transicion ilegal.
     */
    ClaimResult claimReport(String operationId);

    /**
     * Reclamo de la recuperacion de un intento abandonado cuyo commit YA se aplico
     * (PROCESSING con lease vencido o IN_DOUBT -> SUCCEEDED). Nunca re-ejecuta JDBC.
     */
    ClaimResult claimInDoubtRecovery(String operationId);

    /**
     * Reclamo para ejecutar el pipeline desde el inicio (RETRYABLE o PROCESSING abandonado). En una
     * sola operacion comprueba el lease y hace el bump del intento: el segundo que llega ve el lease
     * renovado y no roba el trabajo.
     */
    ClaimResult claimForProcessing(String operationId);

    /**
     * JDBC_COMMITTED -> REPORT_PENDING (reporte en curso hacia la API) para el DUENO del commit.
     * Devuelve false si la operacion ya esta SUCCEEDED (otra entrega la completo mientras este
     * hilo perdia su lease): en ese caso NO corresponde volver a reportar.
     */
    boolean markReportPending(String operationId);

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