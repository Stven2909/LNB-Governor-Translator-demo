package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.QuarantineType;
import com.pagaduriasintetica.worker.contract.ReportResult;
import com.pagaduriasintetica.worker.contract.ReportStatus;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.governor.GovernorUnavailableException;
import com.pagaduriasintetica.worker.translator.Translator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pruebas de frontera del Bloque 1 (revision de Carlos). Cubren el principio LNB "commit significa
 * exito" y los limites de reintento, que antes no tenian prueba:
 *
 * - Un fallo del REPORTE con Sybase ya confirmado NO puede quarantinear la operacion: el pago ya
 *   ocurrio y quedaria sin registrar.
 * - Un error desconocido tiene reintento LIMITADO; sin tope, un bug de programacion produce un
 *   ciclo perpetuo de Pub/Sub.
 * - Si falla el registro del estado, la respuesta NO puede ser 200: se perderia la trazabilidad.
 * - La cuarentena FUNCIONAL no es la DLQ nativa de Pub/Sub y ahora se distingue explicitamente.
 *
 * Suite propia (no@SpringBootTest) porque necesita controlar el limite de reintentos y forzar
 * fallos por inyeccion de mocks.
 */
class CommitMeansSuccessTest {

    private static final int MAX_UNKNOWN_RETRIES = 3;
    private static final int MAX_REPORT_ATTEMPTS = 3;

    private ObjectMapper mapper;
    private Catalog catalog;
    private InMemoryStateStore store;
    private Governor governor;
    private Translator translator;
    private FixtureJdbcExecutor jdbc;
    private MockResultReporter reporter;
    private WorkerService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = JsonMapper.builder().build();
        catalog = new Catalog(mapper);
        store = new InMemoryStateStore(new OperationStateMachine());
        jdbc = new FixtureJdbcExecutor(store);
        reporter = new MockResultReporter();
        governor = mock(Governor.class);
        translator = mock(Translator.class);
        service = newService(store, jdbc, reporter, governor, translator);
        // Defaults de "todo sale bien". Cada test que necesita un fallo sobrescribe estos stubs:
        // en Mockito el ultimo stubbing gana, por eso handle() NO debe re-moquear.
        BranchingCoverageTestSupport.whenGovernorApproves(governor, "op-cualquiera");
        BranchingCoverageTestSupport.whenTranslatorValid(translator, "op-cualquiera");
    }

    private WorkerService newService(StateStore st, JdbcExecutor jd, MockResultReporter rp,
                                     Governor gv, Translator tr) {
        return new WorkerService(mapper, new PayloadHasher(), new StructuralValidator(),
                catalog, gv, tr, st, jd, rp, MAX_UNKNOWN_RETRIES, MAX_REPORT_ATTEMPTS);
    }

    private ProcessingOutcome handle(String operationId) throws Exception {
        return handle(operationId, 1);
    }

    /** Entrega con un deliveryAttempt concreto (contador de redelivery de Pub/Sub). */
    private ProcessingOutcome handle(String operationId, int deliveryAttempt) throws Exception {
        PaymentCommittedEvent event = validEvent(operationId);
        String data = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(event));
        return service.handleRaw(TestEnvelopeFactory.envelopeRaw(mapper, data, "msg-" + operationId,
                deliveryAttempt));
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 1: fallo del reporte con el commit ya confirmado. El estado NO puede degradarse a
    // RETRYABLE ni a cuarentena: el pago en Sybase ya ocurrio.
    // ---------------------------------------------------------------------------------------------

    @Test
    void falloDelReporteConservaElCommitYNoQuarantina() throws Exception {
        reporter.setOverride(result -> ReportResult.failed("timeout del endpoint de reporte"));

        ProcessingOutcome outcome = handle("op-rep-fail");

        assertFalse(outcome.ack(), "debe reintentarse el reporte");
        assertEquals(1, jdbc.executeCount(), "el JDBC se ejecuto una vez");
        OperationState state = store.get("op-rep-fail");
        assertEquals(OperationStatus.REPORT_PENDING, state.status(),
                "el estado debe seguir diciendo que el commit esta confirmado");
        assertEquals(ReportStatus.PENDING, state.reportStatus());
        assertFalse(state.manualActionRequired(), "aun quedan reintentos, no hay accion manual aun");
        assertNull(state.quarantineType(), "no es una cuarentena de ningun tipo");
        assertEquals(1, store.paymentCount("op-rep-fail"), "la fila del pago existe: el commit ocurrio");
    }

    @Test
    void redeliveryTrasFalloDeReporteReintentaSoloElReporte() throws Exception {
        reporter.setOverride(result -> ReportResult.failed("fallo temporal"));
        handle("op-rep-retry");

        reporter.resetOverride();
        ProcessingOutcome second = handle("op-rep-retry", 2);

        assertTrue(second.ack());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-rep-retry").status());
        assertEquals(1, jdbc.executeCount(), "la redelivery NO puede re-ejecutar JDBC");
        assertEquals(1, store.paymentCount("op-rep-retry"));
    }

    /**
     * Al agotar los reintentos del reporte, la operacion NO entra en cuarentena tecnica: queda con
     * el commit preservado, el reporte bloqueado y accion manual. Ponerla en cuarentena declararia
     * fallida una operacion cuyo dinero ya se movio.
     */
    @Test
    void reintentosDeReporteAgotadosBloqueanElReporteSinQuarantinar() throws Exception {
        reporter.setOverride(result -> ReportResult.failed("endpoint caido de forma permanente"));

        ProcessingOutcome outcome = handle("op-rep-blocked", MAX_REPORT_ATTEMPTS);

        assertTrue(outcome.ack(), "al agotar reintentos el mensaje sale de la suscripcion");
        OperationState state = store.get("op-rep-blocked");
        assertEquals(OperationStatus.REPORT_PENDING, state.status(),
                "el commit en Sybase sigue confirmado");
        assertEquals(ReportStatus.BLOCKED, state.reportStatus());
        assertTrue(state.manualActionRequired(), "debe quedar explicito que se requiere accion manual");
        assertNull(state.quarantineType(), "no es cuarentena: es un reporte bloqueado");
        assertEquals(1, state.attemptCount(), "el commit no debe generar intentos extra de JDBC");
        assertNotNull(state.errorReason());
        assertTrue(state.errorReason().contains("endpoint caido"), state.errorReason());
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 2: error desconocido (bug) con el commit ya confirmado -> nunca cuarentena tecnica.
    // ---------------------------------------------------------------------------------------------

    @Test
    void errorDesconocidoTrasCommitBloqueaElReporteYPreservaElPago() throws Exception {
        // El reporter revienta con una excepcion no prevista DESPUES de que JDBC confirmo.
        reporter.setOverride(result -> {
            throw new IllegalStateException("bug en el cliente de reporte");
        });

        ProcessingOutcome outcome = handle("op-post-bug", MAX_REPORT_ATTEMPTS);

        assertTrue(outcome.ack());
        OperationState state = store.get("op-post-bug");
        assertEquals(OperationStatus.REPORT_PENDING, state.status());
        assertEquals(ReportStatus.BLOCKED, state.reportStatus());
        assertTrue(state.manualActionRequired());
        assertNull(state.quarantineType(),
                "una operacion ya pagada NUNCA debe entrar en cuarentena tecnica");
        assertEquals(1, store.paymentCount("op-post-bug"), "el pago quedo registrado en Sybase");
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 3: error desconocido ANTES del commit -> reintento limitado y cuarentena TECNICA.
    // ---------------------------------------------------------------------------------------------

    @Test
    void errorDesconocidoAntesDelCommitAgotaIntentosYVaACuarentenaTecnica() throws Exception {
        BranchingCoverageTestSupport.whenGovernorApproves(governor, "op-pre-bug");
        when(translator.translate(any(TranslatorInput.class)))
                .thenThrow(new IllegalStateException("bug en el traductor"));

        // Cada redelivery reclama la operacion y suma un intento (PROCESSING -> RETRYABLE -> PROCESSING).
        ProcessingOutcome last = null;
        for (int attempt = 1; attempt <= MAX_UNKNOWN_RETRIES; attempt++) {
            last = handle("op-pre-bug", attempt);
        }

        assertNotNull(last);
        assertTrue(last.ack(), "al agotar intentos el mensaje sale de la suscripcion");
        assertEquals(OperationStatus.QUARANTINE_TECHNICAL, last.status());
        OperationState state = store.get("op-pre-bug");
        assertEquals(OperationStatus.QUARANTINE_TECHNICAL, state.status());
        assertEquals(QuarantineType.TECHNICAL, state.quarantineType(),
                "la cuarentena tecnica debe distinguirse de la funcional");
        assertEquals(0, store.paymentCount("op-pre-bug"),
                "no hay pago: la falla fue antes del commit, aqui si se puede cuarentena");
    }

    @Test
    void errorDesconocidoAntesDelCommitReintentaMientrasQuedenIntentos() throws Exception {
        BranchingCoverageTestSupport.whenGovernorApproves(governor, "op-pre-retry");
        when(translator.translate(any(TranslatorInput.class)))
                .thenThrow(new IllegalStateException("bug transitorio"));

        ProcessingOutcome first = handle("op-pre-retry", 1);

        assertFalse(first.ack(), "con intentos disponibles se reintenta");
        assertEquals(OperationStatus.RETRYABLE, store.get("op-pre-retry").status());
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 4: la maquina de estados PROHIBE cuarentena tecnica o de configuracion tras el
    // commit. Esta es la garantia de fondo del principio "commit significa exito".
    // ---------------------------------------------------------------------------------------------

    @Test
    void laMaquinaDeEstadosProhibeCuarentenaTrasElCommit() {
        OperationStateMachine machine = new OperationStateMachine();

        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.JDBC_COMMITTED,
                        OperationStatus.QUARANTINE_TECHNICAL),
                "un commit confirmado no puede pasar a cuarentena tecnica");
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.JDBC_COMMITTED,
                        OperationStatus.BLOCKED_CONFIGURATION),
                "un commit confirmado no puede pasar a bloqueo por configuracion");
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.REPORT_PENDING,
                        OperationStatus.QUARANTINE_TECHNICAL),
                "con el reporte pendiente tampoco se puede cuarentena");
        assertThrows(IllegalStateException.class,
                () -> machine.transition(OperationStatus.REPORT_PENDING,
                        OperationStatus.BLOCKED_CONFIGURATION));
    }

    @Test
    void marcarReporteBloqueadoExigeQueElCommitEsteConfirmado() {
        store.reserveAtomic(new OperationState("op-no-commit", "evt-1", "wtr-1", "msg-1",
                "hash", OperationStatus.PROCESSING, 1, null, null, null));

        assertThrows(IllegalStateException.class, () -> store.markReportBlocked("op-no-commit", "x"),
                "no se puede marcar el reporte como bloqueado si no hubo commit");
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 5: si falla el registro del estado NO se puede responder 200, porque se perderia la
    // trazabilidad del mensaje. El NACK obliga a Pub/Sub a reintentarlo.
    // ---------------------------------------------------------------------------------------------

    @Test
    void siFallaElRegistroDelEstadoNoSeRespondeConAck() {
        // Store que falla al registrar: simula un fallo de base de datos al marcar el estado.
        StateStore failing = mock(StateStore.class);
        when(failing.reserveAtomic(any())).thenReturn(new OperationState("op-store-fail", "evt-1",
                "wtr-1", "msg-1", TestEnvelopeFactory.HASH_OP001, OperationStatus.PROCESSING, 1,
                null, null, null));
        org.mockito.Mockito.doThrow(new IllegalStateException("WORKER_OPERATION_STATE no disponible"))
                .when(failing).markRetryable(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        FixtureJdbcExecutor failingJdbc = new FixtureJdbcExecutor(store);
        WorkerService failingService = newService(failing, failingJdbc, reporter, governor, translator);

        // Debe PROPAGAR la excepcion: si se traguera, el controller devolveria 200 y el mensaje
        // se perderia sin estado persisted.
        assertThrows(IllegalStateException.class,
                () -> handleWith(failingService, "op-store-fail"),
                "un fallo al persistir el estado no puede convertirse en ACK");
    }

    private void handleWith(WorkerService svc, String operationId) throws Exception {
        PaymentCommittedEvent event = validEvent(operationId);
        svc.handleRaw(pushBody(mapper, event, "msg-" + operationId));
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 6: la cuarentena FUNCIONAL se distingue de la DLQ nativa de Pub/Sub.
    // ---------------------------------------------------------------------------------------------

    @Test
    void laInvarianteMonetariaEsRechazoDeNegocioNoCuarentena() throws Exception {
        ProcessingOutcome outcome = service.handleRaw(pushBody(mapper,
                invalidMonetaryEvent("op-reject"), "msg-reject"));

        assertTrue(outcome.ack(), "un rechazo de negocio confirma el mensaje");
        assertEquals(OperationStatus.REJECTED, outcome.status());
        OperationState state = store.get("op-reject");
        assertNull(state.quarantineType(), "un REJECTED no es una cuarentena de ningun tipo");
        assertFalse(state.manualActionRequired());
    }

    /** Evento cuyo netAmount no cumple gross - withholding: rechazo por invariante monetaria. */
    private PaymentCommittedEvent invalidMonetaryEvent(String operationId) {
        PaymentCommittedEvent base = validEvent(operationId);
        return new PaymentCommittedEvent(base.eventId(), base.eventType(), base.aggregateType(),
                base.aggregateId(), base.eventVersion(), base.destinationSystem(),
                base.occurredAt(), base.correlationId(), operationId,
                new com.pagaduriasintetica.worker.contract.OperationData("pay-001", "claim-001",
                        "COMMITTED", "CASH", new java.math.BigDecimal("200.00"),
                        new java.math.BigDecimal("50.00"), new java.math.BigDecimal("999.99"), "USD"));
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 7: la cuarentena FUNCIONAL del Governor queda marcada como funcional.
    // ---------------------------------------------------------------------------------------------

    @Test
    void laCuarentinaFuncionalDelGovernorQuedaTipada() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new com.pagaduriasintetica.worker.governor.GovernorInvalidResponseException(
                        "respuesta fuera de la whitelist del catalogo"));

        ProcessingOutcome outcome = handle("op-func-2");

        assertTrue(outcome.ack());
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertEquals(QuarantineType.FUNCTIONAL, store.get("op-func-2").quarantineType(),
                "debe quedar explicito que es cuarentena FUNCIONAL y no la DLQ nativa de Pub/Sub");
    }

    // ---------------------------------------------------------------------------------------------
    // Frontera 8: timeout del Governor antes del JDBC -> transitorio, sin pago, sin doble riesgo.
    // ---------------------------------------------------------------------------------------------

    @Test
    void timeoutDelGovernorAntesDelJdbcNoDejaPagoNiReservaAtascada() throws Exception {
        when(governor.decide(any(GovernorInput.class))).thenAnswer(invocation -> {
            // Simula que Vertex supero el deadline configurado y el adaptador lo reporta.
            throw new GovernorUnavailableException("Vertex deadline exceeded (timeout=10s)");
        });

        ProcessingOutcome outcome = handle("op-timeout");

        assertFalse(outcome.ack());
        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertEquals(0, jdbc.executeCount(), "sin Governor no puede ejecutarse JDBC");
        assertEquals(0, store.paymentCount("op-timeout"));
        assertNotEqualsTerminal(store.get("op-timeout").status());
    }

    /**
     * Un timeout no debe dejar la operacion en un estado terminal: si lo hiciera, la redelivery
     * confirmaria con ACK y el pago quedaria sin procesar para siempre.
     */
    private void assertNotEqualsTerminal(OperationStatus status) {
        assertTrue(status != OperationStatus.SUCCEEDED && status != OperationStatus.DLQ_QUARANTINED
                        && status != OperationStatus.REJECTED,
                "un timeout no debe dejar la operacion en un estado terminal: " + status);
    }
}