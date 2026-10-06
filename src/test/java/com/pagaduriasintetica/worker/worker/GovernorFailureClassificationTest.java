package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.governor.GovernorConfigurationException;
import com.pagaduriasintetica.worker.governor.GovernorInvalidResponseException;
import com.pagaduriasintetica.worker.governor.GovernorUnavailableException;
import com.pagaduriasintetica.worker.translator.Translator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * G4 (revision de Carlos): el fallo del Governor se clasifica en ramas explicitas en vez de dejar
 * que la excepcion suba. Antes de este cambio, cualquier excepcion de Vertex escapaba, Spring
 * devolvia 500 y la reserva quedaba en PROCESSING sin classifying nada.
 *
 * Ramas verificadas:
 * - Timeout / 429 / 5xx -> RETRYABLE + NACK, reserva reclamable.
 * - Respuesta invalida -> cuarentena FUNCIONAL + ACK, sin republicar en la DLQ tecnica.
 * - Config o permisos -> BLOCKED_CONFIGURATION + ACK, sin reintento en loop.
 * - Excepcion no prevista (p. ej. el reporter fallando) -> RETRYABLE, nunca PROCESSING huerfano.
 */
class GovernorFailureClassificationTest {

    private ObjectMapper mapper;
    private InMemoryStateStore store;
    private Governor governor;
    private Translator translator;
    private FixtureJdbcExecutor jdbc;
    private WorkerService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = JsonMapper.builder().build();
        store = new InMemoryStateStore(new OperationStateMachine());
        jdbc = new FixtureJdbcExecutor(store);
        governor = mock(Governor.class);
        translator = mock(Translator.class);
        service = new WorkerService(mapper, new PayloadHasher(), new StructuralValidator(),
                new Catalog(mapper), governor, translator, store, jdbc, new MockResultReporter(), 3, 5);
    }

    private ProcessingOutcome handle(PaymentCommittedEvent event) throws Exception {
        return service.handleRaw(pushBody(mapper, event, "msg-g4"));
    }

    // Rama 1: Vertex no disponible (timeout, 429, 5xx). Es transitorio -> RETRYABLE y NACK para
    // que Pub/Sub programe la redelivery. Critico: NO se ejecuto JDBC, asi que no hay riesgo de
    // doble pago, y la reserva NO puede quedar en PROCESSING.
    @Test
    void vertexNoDisponibleQuedaRetryableYConNack() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new GovernorUnavailableException("Vertex deadline exceeded (30s)"));

        ProcessingOutcome outcome = handle(validEvent("op-g4-timeout"));

        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertFalse(outcome.ack(), "RETRYABLE debe responder NACK para programar la redelivery");
        assertEquals(OperationStatus.RETRYABLE, store.get("op-g4-timeout").status(),
                "la reserva no puede quedar huerfana en PROCESSING");
        assertEquals(0, jdbc.executeCount(), "sin Governor no se ejecuta JDBC");
        verify(translator, never()).translate(any(TranslatorInput.class));
    }

    // La reserva en RETRYABLE debe ser reclamable: la redelivery vuelve a PROCESSING con bump de
    // intento. Si la transicion no existiera, la redelivery caeria al default y NACK en loop.
    @Test
    void retryablePorVertexEsReclamableEnLaRedelivery() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new GovernorUnavailableException("Vertex 503 UNAVAILABLE"));
        handle(validEvent("op-g4-reclaim"));

        assertEquals(OperationStatus.RETRYABLE, store.get("op-g4-reclaim").status());

        // Segunda entrega: el Governor ya responde y la operacion completa.
        BranchingCoverageTestSupport.whenGovernorApproves(governor, "op-g4-reclaim");
        BranchingCoverageTestSupport.whenTranslatorValid(translator, "op-g4-reclaim");
        ProcessingOutcome second = handle(validEvent("op-g4-reclaim"));

        assertTrue(second.ack());
        assertEquals(2, store.get("op-g4-reclaim").attemptCount(), "el reintento debe contar como intento 2");
        assertEquals(1, jdbc.executeCount(), "el JDBC se ejecuta una sola vez, en el segundo intento");
    }

    // Rama 2: respuesta inutilizable (JSON invalido o decision fuera de la whitelist). No es
    // transitorio -> cuarentena FUNCIONAL con ACK. No se republica en la DLQ tecnica de Pub/Sub.
    @Test
    void respuestaInvalidaVaACuarentenaFuncionalConAck() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new GovernorInvalidResponseException("Respuesta no parseable como GovernorContract"));

        ProcessingOutcome outcome = handle(validEvent("op-g4-invalid"));

        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack(), "un mensaje invalido no debe reintentarse en loop");
        assertEquals(OperationStatus.DLQ_QUARANTINED, store.get("op-g4-invalid").status());
        assertEquals(0, jdbc.executeCount());
    }

    // Rama 3: credencial ausente, permiso denegado o modelo no habilitado. No es transitorio ni es
    // culpa del mensaje -> BLOCKED_CONFIGURATION con ACK y accion manual, nunca NACK en loop.
    @Test
    void errorDeConfiguracionQuedaBlockedConfiguration() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new GovernorConfigurationException("Credential not authorized for project"));

        ProcessingOutcome outcome = handle(validEvent("op-g4-config"));

        assertEquals(OperationStatus.BLOCKED_CONFIGURATION, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.BLOCKED_CONFIGURATION, store.get("op-g4-config").status());
        assertEquals(0, jdbc.executeCount());
    }

    // BLOCKED_CONFIGURATION es terminal: una redelivery posterior confirma con ACK en lugar de
    // reabrir un ciclo que no va a progresar.
    @Test
    void redeliveryDeBlockedConfigurationConfirmaConAck() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new GovernorConfigurationException("permission denied"));
        handle(validEvent("op-g4-terminal"));

        ProcessingOutcome redelivery = handle(validEvent("op-g4-terminal"));

        assertTrue(redelivery.ack());
        assertEquals(OperationStatus.BLOCKED_CONFIGURATION, redelivery.status());
    }

    // Rama 4 (ultimo recurso): una excepcion NO prevista, por ejemplo el reporter fallando dentro
    // de la rama de error, no puede quedar oculta ni dejar la reserva en PROCESSING.
    @Test
    void excepcionNoPrevistaNoDejaReservaEnProcessing() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new IllegalStateException("Falla inesperada del adaptador"));

        ProcessingOutcome outcome = handle(validEvent("op-g4-unexpected"));

        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertFalse(outcome.ack());
        assertEquals(OperationStatus.RETRYABLE, store.get("op-g4-unexpected").status(),
                "una excepcion no prevista no puede dejar la reserva en PROCESSING");
    }

    // El traductor tambien puede fallar de forma no prevista: mismo ultimo recurso, y la operacion
    // no llega a JDBC.
    @Test
    void excepcionNoPrevistaEnElTraductorTampocoDejaProcessing() throws Exception {
        BranchingCoverageTestSupport.whenGovernorApproves(governor, "op-g4-tx");
        when(translator.translate(any(TranslatorInput.class)))
                .thenThrow(new IllegalStateException("Falla del traductor"));

        ProcessingOutcome outcome = handle(validEvent("op-g4-tx"));

        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertEquals(OperationStatus.RETRYABLE, store.get("op-g4-tx").status());
        assertEquals(0, jdbc.executeCount(), "sin plan traducido no se ejecuta JDBC");
    }

    // El motivo persistido es lo que permitira diagnosticar en operacion: debe distinguirse el
    // fallo clasificado de Vertex de la excepcion no prevista.
    @Test
    void elMotivoPersistidoDistingueClasificacionDeExcepcionNoPrevista() throws Exception {
        when(governor.decide(any(GovernorInput.class)))
                .thenThrow(new IllegalStateException("detalle-interno"));
        handle(validEvent("op-g4-motivo"));

        String error = store.get("op-g4-motivo").errorReason();

        assertTrue(error.contains("detalle-interno"),
                "el motivo debe quedar persistido para diagnostico: " + error);
    }
}