package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.QuarantineType;
import com.pagaduriasintetica.worker.contract.ReportStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Mock en memoria del almacenamiento para la PoC: replica WORKER_OPERATION_STATE (control de
 * idempotencia/estado) y SYNTHETIC_PAYMENTS (tabla destino del pago del fixture).
 * reserveAtomic usa putIfAbsent = INSERT de la PK operationId (base del diseño de LNB contra
 * dobles envíos de Pub/Sub). TODAS las mutaciones de estado pasan por OperationStateMachine:
 * una transición inválida (p. ej. SUCCEEDED -> PROCESSING) lanza IllegalStateException.
 * NOTA de atomicidad: fixture y estado viven en la misma "base sintética", así que el INSERT+
 * UPDATE es atómico local; esto NO demuestra atomicidad Sybase-Worker en la integración real.
 *
 * Atomicidad (bloque 2, revisión de Carlos): cada mutación de estado se aplica con
 * ConcurrentHashMap.compute, de modo que la lectura del estado vigente, la validación de la
 * transición y la escritura ocurren como una sola operación sobre la clave. Con get-then-put dos
 * hilos podían leer el mismo estado y sobrescribirse (actualización perdida): por ejemplo, dos
 *markedProcessing simultáneos bumpaban el intento una sola vez, o un RETRYABLE ya promovido a
 * SUCCEEDED era degradado por una escritura atrasada.
 *
 * Lease con expiración: marca que una operación está en vuelo y hasta cuándo. Sin ella, una
 * operación que quedó en PROCESSING por un cierre de instancia (crash) bloqueaba la redelivery de
 * forma indefinida. Un lease vigente significa "otro hilo o instancia está trabajando en esto";
 * un lease vencido o ausente significa "el intento anterior murió y se puede reclamar".
 *
 * LÍMITE CONOCIDO: compute() es atómico solo dentro de una instancia. Con varias instancias de
 * Cloud Run este mapa en memoria no se comparte, así que el control real de exclusión debe venir
 * del almacenamiento durable (clave única por operationId, reserva atómica, row_version o control
 * optimista, y lease con expiración como columna). Este fixture sirve para probar la semántica,
 * no para demostrar exclusión entre procesos.
 */
@Component
public class InMemoryStateStore implements StateStore {

    /** 60 s: coherente con el ackDeadline de la suscripción y con el timeout de Vertex. */
    public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);

    private final ConcurrentMap<String, OperationState> states = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PaymentRow> payments = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Instant> leases = new ConcurrentHashMap<>();
    private final List<String> quarantineAudit = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final OperationStateMachine machine;
    private final Duration leaseDuration;
    private final Clock clock;

    /** Constructor de produccion: lease de 60 s y reloj del sistema. */
    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryStateStore(OperationStateMachine machine) {
        this(machine, DEFAULT_LEASE_DURATION, Clock.systemUTC());
    }

    /** Constructor con reloj inyectable para probar la expiracion del lease de forma determinista. */
    public InMemoryStateStore(OperationStateMachine machine, Duration leaseDuration, Clock clock) {
        this.machine = machine;
        this.leaseDuration = leaseDuration;
        this.clock = clock;
    }

    @Override
    public boolean leaseIsHeldAndFresh(String operationId) {
        Instant expiry = leases.get(operationId);
        return expiry != null && clock.instant().isBefore(expiry);
    }

    @Override
    public void releaseLease(String operationId) {
        leases.remove(operationId);
    }

    @Override
    public Instant leaseExpiry(String operationId) {
        return leases.get(operationId);
    }

    @Override
    public OperationState reserveAtomic(OperationState initial) throws DuplicateOperationException {
        // compute y no putIfAbsent + leases.put: verificar que no exista y tomar el lease deben ser
        // una sola operacion sobre la clave. Con dos pasos, un duplicado que llegara entre ambos
        // veria PROCESSING sin lease y creeria que el intento anterior murio, robando la operacion
        // que el primer hilo todavia estaba ejecutando (doble pago).
        boolean[] duplicate = {false};
        OperationState reserved = states.compute(initial.operationId(), (key, current) -> {
            if (current != null) {
                duplicate[0] = true;
                return current;
            }
            // La operacion queda en vuelo desde el momento de la reserva.
            leases.put(key, clock.instant().plus(leaseDuration));
            return initial;
        });
        if (duplicate[0]) {
            throw new DuplicateOperationException(initial.operationId());
        }
        return reserved;
    }

    @Override
    public OperationState get(String operationId) {
        return states.get(operationId);
    }

    @Override
    public PaymentRow findPayment(String operationId) {
        return payments.get(operationId);
    }

    /**
     * Aplica una transicion de estado de forma atomica por clave y actualiza el lease en la misma
     * operacion. keepLease=true mientras la operacion sigue en vuelo (reclamar un intento nuevo);
     * los estados terminales liberan el lease porque ya no hay nada que ejecutar.
     */
    private OperationState transition(String operationId, OperationStatus target, boolean keepLease,
                                      java.util.function.UnaryOperator<OperationState> decorator) {
        return states.compute(operationId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("No OperationState reserved for " + key);
            }
            OperationState next = decorator.apply(current.withStatus(machine.transition(current.status(), target)));
            if (keepLease) {
                leases.put(key, clock.instant().plus(leaseDuration));
            } else {
                leases.remove(key);
            }
            return next;
        });
    }

    @Override
    public void markRejected(String operationId, String reason) {
        transition(operationId, OperationStatus.REJECTED, false, s -> s.withError(reason));
    }

    @Override
    public OperationState markProcessing(String operationId) {
        // Reclamar un intento nuevo. La revalidacion del lease ocurre DENTRO del compute: si el
        // WorkerService comprueba el lease y despues llama a este metodo, otro hilo podria tomar
        // la operacion en ese medio tiempo y ambos la ejecutarian. Aqui el segundo que llega ve el
        // lease ya renovado y no puede robar el trabajo en curso.
        return states.compute(operationId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("No OperationState reserved for " + key);
            }
            if (current.status() == OperationStatus.PROCESSING) {
                Instant expiry = leases.get(key);
                if (expiry != null && clock.instant().isBefore(expiry)) {
                    throw new IllegalStateException("Operation in flight with fresh lease: " + key);
                }
            }
            OperationState next = current.withStatus(
                    machine.transition(current.status(), OperationStatus.PROCESSING)).bumped();
            leases.put(key, clock.instant().plus(leaseDuration));
            return next;
        });
    }

    @Override
    public void markRetryable(String operationId, String reason) {
        // El lease se libera: la operacion vuelve a ser reclamable por la redelivery.
        transition(operationId, OperationStatus.RETRYABLE, false, s -> s.withError(reason));
    }

    @Override
    public void markInDoubt(String operationId, String reason) {
        // IN_DOUBT no esta en vuelo: espera conciliacion, asi que no debe retener el lease.
        transition(operationId, OperationStatus.IN_DOUBT, false, s -> s.withError(reason));
    }

    @Override
    public void markReportPending(String operationId) {
        // El reporte sigue en vuelo: conserva el lease y deja el commit confirmado como PENDING.
        transition(operationId, OperationStatus.REPORT_PENDING, true,
                s -> s.withReportStatus(ReportStatus.PENDING));
    }

    @Override
    public void markSucceeded(String operationId) {
        transition(operationId, OperationStatus.SUCCEEDED, false,
                s -> s.withReportStatus(ReportStatus.SUCCEEDED)
                        .withManualActionRequired(false)
                        .withDecision("APPROVED",
                                s.targetTable() == null ? "-" : s.targetTable(),
                                "Operación validada correctamente"));
    }

    @Override
    public void markQuarantined(String operationId, String reason) {
        // Cuarentena FUNCIONAL: el mensaje es invalido para el negocio, no fallo de infraestructura.
        // No es la DLQ nativa de Pub/Sub; por eso se marca el tipo de forma explicita.
        transition(operationId, OperationStatus.DLQ_QUARANTINED, false,
                s -> s.withQuarantineType(QuarantineType.FUNCTIONAL).withError(reason));
    }

    @Override
    public void markReportRetryable(String operationId, String reason) {
        // Fallo transitorio del reporte: NO se toca el status del pipeline. La operacion sigue en
        // JDBC_COMMITTED / REPORT_PENDING porque el commit ya ocurrio, y el reporte queda PENDING
        // para el siguiente intento. Degradar a RETRYABLE perderia esa distincion.
        states.compute(operationId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("No OperationState reserved for " + key);
            }
            if (current.status() != OperationStatus.JDBC_COMMITTED
                    && current.status() != OperationStatus.REPORT_PENDING) {
                throw new IllegalStateException(
                        "markReportRetryable requires a committed operation, but " + key
                                + " is " + current.status());
            }
            leases.remove(key);
            return current.withStatus(OperationStatus.REPORT_PENDING)
                    .withReportStatus(ReportStatus.PENDING)
                    .withError(reason);
        });
    }

    @Override
    public void markTranslationError(String operationId, String reason) {
        transition(operationId, OperationStatus.TRANSLATION_ERROR, false, s -> s.withError(reason));
    }

    @Override
    public void markBlockedConfiguration(String operationId, String reason) {
        transition(operationId, OperationStatus.BLOCKED_CONFIGURATION, false, s -> s.withError(reason));
    }

    @Override
    public void markQuarantineTechnical(String operationId, String reason) {
        transition(operationId, OperationStatus.QUARANTINE_TECHNICAL, false,
                s -> s.withQuarantineType(QuarantineType.TECHNICAL).withError(reason));
    }

    @Override
    public void markReportBlocked(String operationId, String reason) {
        // El status NO cambia: el commit en Sybase ya ocurrio y esa es la verdad economica. Solo se
        // marca el reporte como bloqueado y se exige accion manual. La transicion se valida contra el
        // estado vigente (debe ser JDBC_COMMITTED o REPORT_PENDING) para no usarlo como atajo.
        states.compute(operationId, (key, current) -> {
            if (current == null) {
                throw new IllegalStateException("No OperationState reserved for " + key);
            }
            if (current.status() != OperationStatus.JDBC_COMMITTED
                    && current.status() != OperationStatus.REPORT_PENDING) {
                throw new IllegalStateException(
                        "markReportBlocked requires a committed operation, but " + key
                                + " is " + current.status());
            }
            leases.remove(key);
            return current.withReportStatus(ReportStatus.BLOCKED)
                    .withManualActionRequired(true)
                    .withError(reason);
        });
    }

    @Override
    public void update(OperationState state) {
        // Escritura administrativa: valida la transición contra el estado vigente y libera el lease,
        // porque quien escribe no esta ejecutando la operacion.
        states.compute(state.operationId(), (key, current) -> {
            machine.transition(current == null ? null : current.status(), state.status());
            leases.remove(key);
            return state;
        });
    }

    @Override
    public void recordMalformed(String key, String reason) {
        quarantineAudit.add("key=" + key + " reason=" + reason);
    }

    /**
     * Transacción del fixture (usada por FixtureJdbcExecutor): INSERT en SYNTHETIC_PAYMENTS y
     * paso a JDBC_COMMITTED en un solo paso atómico de la base sintética.
     */
    public synchronized void commitPayment(PaymentRow payment) {
        if (payments.putIfAbsent(payment.operationId(), payment) != null) {
            throw new DuplicateOperationException(payment.operationId());
        }
        // El commit sigue en vuelo (falta el reporte): conserva el lease.
        transition(payment.operationId(), OperationStatus.JDBC_COMMITTED, true, s -> s.withDecision(
                "APROBADO", payment.targetTable(), "Operación validada correctamente"));
    }

    /** Fixture: resultado indeterminado (UNKNOWN) -> fila persistida + estado IN_DOUBT. */
    public synchronized void commitPaymentInDoubt(PaymentRow payment) {
        if (payments.putIfAbsent(payment.operationId(), payment) != null) {
            throw new DuplicateOperationException(payment.operationId());
        }
        // IN_DOUBT no esta en vuelo: espera conciliacion, asi que libera el lease.
        transition(payment.operationId(), OperationStatus.IN_DOUBT, false,
                s -> s.withError("Indeterminate JDBC result (commit posiblemente aplicado)"));
    }

    public int paymentCount(String operationId) {
        return payments.containsKey(operationId) ? 1 : 0;
    }

    public List<String> quarantineAudit() {
        return List.copyOf(quarantineAudit);
    }

    public void reset() {
        states.clear();
        payments.clear();
        leases.clear();
        quarantineAudit.clear();
    }

    public synchronized void seedDoubtful(String operationId, String stateHash, String rowHash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-doubt", "wtr-doubt", "msg-doubt",
                stateHash, OperationStatus.PROCESSING, 1, null, null, null));
        payments.put(operationId, new PaymentRow(operationId, rowHash, targetTable, parameters));
    }

    /** Fixture IN_DOUBT = fila persistida + estado indeterminado (para conciliar por PAYLOAD_HASH).
     *  rowHash distinto de stateHash simula la inconsistencia que termina en DLQ. */
    public synchronized void seedInDoubt(String operationId, String stateHash, String rowHash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-in-doubt", "wtr-in-doubt", "msg-in-doubt",
                stateHash, OperationStatus.IN_DOUBT, 1, null, null, "Indeterminate JDBC result (commit posiblemente aplicado)"));
        payments.put(operationId, new PaymentRow(operationId, rowHash, targetTable, parameters));
    }

    /**
     * Fixture: estado JDBC_COMMITTED con su fila de pago ya persistida (crash tras el commit,
     * antes del reporte). La redelivery SÓLO debe reintentar el reporte, nunca re-ejecutar JDBC.
     */
    public synchronized void seedJdbcCommitted(String operationId, String hash, String targetTable, List<Object> parameters) {
        states.put(operationId, new OperationState(operationId, "evt-jdbc", "wtr-jdbc", "msg-jdbc",
                hash, OperationStatus.JDBC_COMMITTED, 1, "APPROVED", targetTable, null));
        payments.put(operationId, new PaymentRow(operationId, hash, targetTable, parameters));
    }

    private OperationState require(String operationId) {
        OperationState current = states.get(operationId);
        if (current == null) {
            throw new IllegalStateException("No OperationState reserved for " + operationId);
        }
        return current;
    }
}