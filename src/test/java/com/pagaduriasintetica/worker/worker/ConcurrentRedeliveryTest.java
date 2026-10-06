package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.governor.MockGovernor;
import com.pagaduriasintetica.worker.translator.DeterministicTranslator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.HASH_OP001;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bloque 2 (revision de Carlos): idempotencia y exclusion bajo concurrencia.
 *
 * Con get-then-put dos hilos que leen el mismo estado lo sobrescriben (actualizacion perdida):
 * dos markedProcessing simultaneos bumpaban el intento una sola vez, y una escritura atrasada
 * podia degradar un estado terminal ya alcanzado. Con compute la lectura, la validacion de la
 * transicion y la escritura son una sola operacion sobre la clave.
 *
 * Lease con expiracion: un PROCESSING puede ser una operacion realmente en vuelo o el resto de un
 * cierre de instancia. Con lease vigente no se roba ni se infla el contador de intentos; con lease
 * vencido se reclama y se reintenta. Sin este mecanismo, el segundo caso bloquea la redelivery para
 * siempre (requisito de recuperacion de la JD).
 *
 * LIMITE CONOCIDO: compute es atomico dentro de una instancia. Con varias instancias de Cloud Run
 * este mapa no se comparte y la exclusion real debe venir del almacenamiento durable.
 */
@SpringBootTest
class ConcurrentRedeliveryTest {

    private static final int THREADS = 16;

    @Autowired
    ObjectMapper mapper;
    @Autowired
    InMemoryStateStore store;
    @Autowired
    MockGovernor governor;
    @Autowired
    MockResultReporter reporter;
    @Autowired
    FixtureJdbcExecutor jdbc;
    @Autowired
    WorkerService workerService;

    private int jdbcBaseline;

    @BeforeEach
    void reset() {
        store.reset();
        governor.resetOverride();
        reporter.resetOverride();
        jdbc.resetOutcome();
        // executeCount() es acumulativo (bean singleton): se mide el delta por caso.
        jdbcBaseline = jdbc.executeCount();
    }

    private int jdbcDelta() {
        return jdbc.executeCount() - jdbcBaseline;
    }

    private ProcessingOutcome handle(PaymentCommittedEvent event) throws Exception {
        return workerService.handleRaw(pushBody(mapper, event, "msg-conc"));
    }

    /**
     * N entregas simultaneas del MISMO operationId. Requisito de Carlos: el ejecutor JDBC debe
     * invocarse una sola vez y la fila de pago no puede duplicarse.
     */
    @Test
    void entregasSimultaneasDelMismoOperationIdEjecutanJdbcUnaSolaVez() throws Exception {
        PaymentCommittedEvent event = validEvent("op-conc-1");
        String raw = pushBody(mapper, event, "msg-conc-1");
        List<ProcessingOutcome> outcomes = inParallel(THREADS, () -> workerService.handleRaw(raw));

        assertEquals(1, jdbcDelta(), "el JDBC debe ejecutarse exactamente una vez");
        assertEquals(1, store.paymentCount("op-conc-1"), "la fila de pago no puede duplicarse");
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-conc-1").status());
        assertEquals(1, store.get("op-conc-1").attemptCount(), "un solo intento debe contar");

        // Ninguna entrega inventa un estado: la que hace el trabajo termina en SUCCEEDED y las demas
        // son idempotentes o se devuelven para redelivery programada.
        for (ProcessingOutcome outcome : outcomes) {
            assertTrue(List.of(OperationStatus.SUCCEEDED, OperationStatus.IDEMPOTENT,
                            OperationStatus.RETRYABLE).contains(outcome.status()),
                    "estado inesperado en entrega concurrente: " + outcome);
        }
        assertTrue(outcomes.stream().anyMatch(ProcessingOutcome::ack),
                "al menos una entrega debe confirmar (ACK) el resultado");
    }

    /**
     * Actualizacion perdida: N markedProcessing sobre el mismo PROCESSING (lease vencido) deben
     * producir una sola reclamacion. Con get-then-put, los N leian PROCESSING, todos bumpaban y
     * el ultimo write dejaba attemptCount en 2; y si ademas uno ejecutaba el flujo, habria doble
     * pago. Con compute, solo uno gana y los demas reciben IllegalStateException.
     */
    @Test
    void soloUnHiloReclamaUnIntentoAbandonado() throws Exception {
        reserveProcessing("op-conc-2", 1);
        store.releaseLease("op-conc-2");

        AtomicReference<Integer> attempts = new AtomicReference<>();
        runInParallel(THREADS, () -> {
            try {
                attempts.set(store.markProcessing("op-conc-2").attemptCount());
            } catch (IllegalStateException e) {
                // Losing races: el trabajo ya esta en manos de otro hilo.
            }
        });

        assertEquals(2, store.get("op-conc-2").attemptCount(), "exactamente un intento nuevo");
        assertEquals(2, attempts.get(), "solo un hilo debe completar la transicion");
    }

    /** Una transicion invalida no se pierde en una carrera: el perdedor falla de forma explicita. */
    @Test
    void unEstadoTerminalNoSeDegradaPorEscrituraConcurrente() throws Exception {
        reserveProcessing("op-conc-3", 1);
        // El camino real es PROCESSING -> JDBC_COMMITTED -> REPORT_PENDING -> SUCCEEDED; acortar
        // con PROCESSING->SUCCEEDED seria una transicion ilegal.
        store.commitPayment(new PaymentRow("op-conc-3", HASH_OP001, "SYNTHETIC_PAYMENTS", List.of()));
        store.markReportPending("op-conc-3");
        store.markSucceeded("op-conc-3");

        AtomicReference<Exception> failure = new AtomicReference<>();
        runInParallel(THREADS, () -> {
            try {
                store.markRetryable("op-conc-3", "escritura atrasada");
            } catch (IllegalStateException e) {
                failure.compareAndSet(null, e);
            }
        });

        assertNotNull(failure.get(), "una escritura que degrada un terminal debe fallar explicitamente");
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-conc-3").status(),
                "el estado ganador no debe cambiarse");
    }

    /** Con lease vigente la operacion en vuelo no se roba y el intento NO se infla. */
    @Test
    void conLeaseVigenteLaOperacionEnVueloNoSeRoba() throws Exception {
        reserveProcessing("op-lease-1", 1);
        assertTrue(store.leaseIsHeldAndFresh("op-lease-1"), "la reserva debe tomar un lease");

        ProcessingOutcome outcome = handle(validEvent("op-lease-1"));

        assertFalse(outcome.ack(), "una operacion en vuelo debe devolver NACK, no robar el trabajo");
        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertEquals(1, store.get("op-lease-1").attemptCount(), "con lease vigente el intento NO se infla");
        assertEquals(0, jdbcDelta(), "no se ejecuta JDBC de una operacion en vuelo");
    }

    /** Un lease se libera al terminar, para que una redelivery posterior no lo lea como vigente. */
    @Test
    void elLeaseSeLiberaAlTerminarLaOperacion() throws Exception {
        handle(validEvent("op-lease-3"));

        assertEquals(OperationStatus.SUCCEEDED, store.get("op-lease-3").status());
        assertNull(store.leaseExpiry("op-lease-3"), "una operacion terminada no debe retener lease");
    }

    /**
     * Lease vencido = intento anterior muerto (crash o cierre de instancia): la redelivery reclama
     * la operacion y ejecuta el flujo completo. Es el requisito de recuperacion de la JD.
     */
    @Test
    void conLeaseVencidoLaOperacionSeReclamaYSeReintenta() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-05T00:00:00Z"));
        InMemoryStateStore leased = new InMemoryStateStore(new OperationStateMachine(),
                Duration.ofSeconds(30), new MutableClock(now));
        FixtureJdbcExecutor leasedJdbc = new FixtureJdbcExecutor(leased);
        MockResultReporter leasedReporter = new MockResultReporter();
        Catalog catalog = new Catalog(mapper);
        WorkerService leasedService = new WorkerService(mapper, new PayloadHasher(),
                new StructuralValidator(), catalog, new MockGovernor(catalog),
                new DeterministicTranslator(catalog), leased, leasedJdbc, leasedReporter, 3, 5);

        leased.reserveAtomic(new OperationState("op-lease-2", "evt-001", "wtr-1", "msg-1",
                HASH_OP001, OperationStatus.PROCESSING, 1, null, null, null));
        assertTrue(leased.leaseIsHeldAndFresh("op-lease-2"));

        now.set(Instant.parse("2026-10-05T00:05:00Z"));
        assertFalse(leased.leaseIsHeldAndFresh("op-lease-2"), "el lease debe estar vencido");

        // La entrega que encuentra el lease vencido reclama la operacion y ejecuta el flujo completo
        // en el mismo intento, sin esperar a que expire otro lease.
        ProcessingOutcome outcome = leasedService.handleRaw(pushBody(mapper, validEvent("op-lease-2"), "m2"));

        assertTrue(outcome.ack(), "tras reclamar, la operacion debe completarse en el mismo intento");
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(2, leased.get("op-lease-2").attemptCount(), "reclamar cuenta un intento nuevo");
        assertEquals(1, leasedJdbc.executeCount(), "el flujo se ejecuta exactamente una vez");
        assertEquals(OperationStatus.SUCCEEDED, leased.get("op-lease-2").status());
        assertEquals(1, leased.paymentCount("op-lease-2"), "no debe duplicarse la fila");
    }

    /** Operaciones independientes en paralelo no interfieren entre si (sin exclusion global). */
    @Test
    void operacionesDistintasEnParaleloNoSeInterfieren() throws Exception {
        int operations = 12;
        runInParallel(operations, () -> {
            try {
                String id = "op-par-" + Thread.currentThread().getId();
                ProcessingOutcome outcome = workerService.handleRaw(pushBody(mapper, validEvent(id), "msg-" + id));
                assertEquals(OperationStatus.SUCCEEDED, outcome.status(), id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertEquals(operations, jdbcDelta(), "cada operacion independiente ejecuta su JDBC");
    }

    private void reserveProcessing(String operationId, int attempt) {
        store.reserveAtomic(new OperationState(operationId, "evt-001", "wtr-1", "msg-1",
                HASH_OP001, OperationStatus.PROCESSING, attempt, null, null, null));
    }

    /** Ejecuta la accion en n hilos que arrancan a la vez; falla si algun hilo lanza. */
    private void runInParallel(int threads, ThrowingRunnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    action.run();
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        if (!errors.isEmpty()) {
            throw new AssertionError("hilos con excepcion: " + errors, errors.peek());
        }
    }

    /** Igual que runInParallel, pero recolecta los resultados de cada hilo. */
    private <T> List<T> inParallel(int threads, ResultProducer<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<T> results = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    results.add(action.get());
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        if (!errors.isEmpty()) {
            throw new AssertionError("hilos con excepcion: " + errors, errors.peek());
        }
        return List.copyOf(results);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface ResultProducer<T> {
        T get() throws Exception;
    }

    /** Reloj mutable para simular el paso del tiempo y la expiracion del lease de forma determinista. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(AtomicReference<Instant> now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}