package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.config.WorkerProperties;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorEnrichmentStatus;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.translator.Translator;
import com.pagaduriasintetica.worker.worker.FixtureJdbcExecutor;
import com.pagaduriasintetica.worker.worker.InMemoryStateStore;
import com.pagaduriasintetica.worker.worker.MockResultReporter;
import com.pagaduriasintetica.worker.worker.OperationStateMachine;
import com.pagaduriasintetica.worker.worker.PayloadHasher;
import com.pagaduriasintetica.worker.worker.StructuralValidator;
import com.pagaduriasintetica.worker.worker.TestEnvelopeFactory;
import com.pagaduriasintetica.worker.worker.WorkerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Matriz fail-closed de VertexGovernor (Q1-Q3 JD) con cliente fake, sin red ni credenciales:
 * el veto restrictivo prevalece como REJECTED y los errores tecnicos son excepciones tipadas,
 * nunca decisiones forjadas. Sin JDBC en ningun camino de veto o fallo (verificado a nivel
 * Worker en vetoDeVertexNoEjecutaJdbc).
 */
class VertexGovernorTest {

    private static final String APPROVED_JSON = """
            {"decision":"APPROVED","reason":"Evento y traduccion equivalentes.",
             "summary":"Operacion consistente.","observations":["monto dentro de rango"],
             "suggested_mapping":{"status":"ESTADO"}}""";

    private static final String VETO_JSON = """
            {"decision":"REJECTED","reason":"La traduccion anula un pago de premio confirmado.",
             "summary":"Traduccion incongruente.","observations":["sentido economico invertido"],
             "suggested_mapping":{}}""";

    private ObjectMapper mapper;
    private DeterministicGovernorSupport support;
    private WorkerProperties.Vertex props;
    private FakeClient fake;

    /** Fake programable del adaptador Vertex: devuelve un outcome o lanza la falla indicada. */
    private static final class FakeClient implements VertexClient {
        VertexOutcome outcome = VertexOutcome.text(APPROVED_JSON);
        RuntimeException failure;

        @Override
        public VertexOutcome generate(String systemInstruction, String userPrompt) {
            if (failure != null) {
                throw failure;
            }
            return outcome;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        mapper = JsonMapper.builder().build();
        support = new DeterministicGovernorSupport(new Catalog(mapper));
        props = new WorkerProperties.Vertex();
        fake = new FakeClient();
    }

    private VertexGovernor governor() throws Exception {
        return new VertexGovernor(fake, support, props, mapper);
    }

    private GovernorInput input(String operationId) {
        return new GovernorInput(TestEnvelopeFactory.validEvent(operationId), "wtr-test");
    }

    @Test
    void approvedSeAdjuntaConDecisionDeterministica() throws Exception {
        GovernorContract contract = governor().decide(input("op-v-attached"));

        assertEquals(GovernorDecision.APPROVED, contract.decision());
        assertEquals(GovernorEnrichmentStatus.ATTACHED, contract.enrichmentStatus());
        assertEquals("Operacion consistente.", contract.enrichment().summary());
        assertEquals("ESTADO", contract.enrichment().suggestedMapping().get("status"));
    }

    @Test
    void vetoPrevaleceComoRejectedConMotivoYEnriquecimiento() throws Exception {
        fake.outcome = VertexOutcome.text(VETO_JSON);

        GovernorContract contract = governor().decide(input("op-v-veto"));

        assertEquals(GovernorDecision.REJECTED, contract.decision());
        assertTrue(contract.reason().contains("anula un pago"),
                "el motivo de Vertex debe preservarse para revision: " + contract.reason());
        assertEquals(GovernorEnrichmentStatus.ATTACHED, contract.enrichmentStatus());
        assertEquals("Traduccion incongruente.", contract.enrichment().summary());
    }

    @Test
    void decisionDesconocidaEsRespuestaInvalida() {
        fake.outcome = VertexOutcome.text(
                "{\"decision\":\"MAYBE\",\"reason\":\"duda\",\"summary\":\"s\",\"observations\":[]}");

        assertThrows(GovernorInvalidResponseException.class,
                () -> governor().decide(input("op-v-unknown")));
    }

    @Test
    void reasonAusenteEsSchemaViolation() {
        fake.outcome = VertexOutcome.text(
                "{\"decision\":\"APPROVED\",\"summary\":\"s\",\"observations\":[]}");

        GovernorInvalidResponseException thrown = assertThrows(
                GovernorInvalidResponseException.class, () -> governor().decide(input("op-v-noreason")));
        assertTrue(thrown.getMessage().contains(VertexGovernor.CODE_SCHEMA_VIOLATION));
    }

    @Test
    void safetyBlockEsRespuestaInvalidaNoDegradacion() {
        fake.outcome = VertexOutcome.blocked("SAFETY");

        GovernorInvalidResponseException thrown = assertThrows(
                GovernorInvalidResponseException.class, () -> governor().decide(input("op-v-safety")));
        assertTrue(thrown.getMessage().contains(VertexGovernor.CODE_SAFETY_BLOCK));
        assertFalse(thrown.getMessage().contains("TRUNCATED"),
                "un bloqueo de seguridad nunca es truncado");
    }

    @Test
    void textoVacioEsRespuestaInvalida() {
        fake.outcome = VertexOutcome.text("   ");

        assertThrows(GovernorInvalidResponseException.class,
                () -> governor().decide(input("op-v-empty")));
    }

    @Test
    void outcomeNuloEsRespuestaInvalida() {
        fake.outcome = null;

        assertThrows(GovernorInvalidResponseException.class,
                () -> governor().decide(input("op-v-null")));
    }

    @Test
    void jsonMalformadoEsRespuestaInvalida() {
        fake.outcome = VertexOutcome.text("{no-es-json");

        GovernorInvalidResponseException thrown = assertThrows(
                GovernorInvalidResponseException.class, () -> governor().decide(input("op-v-badjson")));
        assertTrue(thrown.getMessage().contains(VertexGovernor.CODE_INVALID));
    }

    @Test
    void maxTokensEsTransitorioNoSafety() {
        fake.outcome = VertexOutcome.truncated("MAX_TOKENS");

        GovernorUnavailableException thrown = assertThrows(GovernorUnavailableException.class,
                () -> governor().decide(input("op-v-trunc")));
        assertTrue(thrown.getMessage().contains(VertexGovernor.CODE_TRUNCATED));
        assertFalse(thrown.getMessage().contains("SAFETY"),
                "MAX_TOKENS es insuficiencia de presupuesto, no bloqueo");
    }

    @Test
    void transitoriosLanzanUnavailableSinContrato() throws Exception {
        for (int code : new int[]{408, 429, 500, 503, -1}) {
            fake.outcome = VertexOutcome.text(APPROVED_JSON);
            fake.failure = new VertexCallException(code, "t", "falla", null);

            assertThrows(GovernorUnavailableException.class,
                    () -> governor().decide(input("op-v-t-" + code)),
                    "codigo " + code + " debe reintentar, no forjar decision");
        }
    }

    @Test
    void fallosDeConfiguracionLanzanGovernorConfigurationException() throws Exception {
        for (int code : new int[]{401, 403, 404}) {
            fake.failure = new VertexCallException(code, "config", "falla-" + code, null);

            GovernorConfigurationException thrown = assertThrows(
                    GovernorConfigurationException.class,
                    () -> governor().decide(input("op-v-cfg-" + code)));
            assertTrue(thrown.getMessage().contains(String.valueOf(code)));
        }
    }

    @Test
    void excepcionNoPrevistaDelClienteEsTransitoria() {
        fake.failure = new IllegalStateException("falla inesperada del adaptador");

        assertThrows(GovernorUnavailableException.class,
                () -> governor().decide(input("op-v-unexpected")));
    }

    @Test
    void mensajesNoExponenSecretos() {
        fake.failure = new VertexCallException(503, "unavailable", "cuota excedida", null);
        GovernorUnavailableException unavailable = assertThrows(GovernorUnavailableException.class,
                () -> governor().decide(input("op-v-sanit")));
        fake.failure = new VertexCallException(403, "denied", "permiso denegado", null);
        GovernorConfigurationException config = assertThrows(GovernorConfigurationException.class,
                () -> governor().decide(input("op-v-sanit-cfg")));
        fake.outcome = VertexOutcome.blocked("SAFETY");
        fake.failure = null;
        GovernorInvalidResponseException invalid = assertThrows(GovernorInvalidResponseException.class,
                () -> governor().decide(input("op-v-sanit-inv")));

        for (String s : new String[]{unavailable.getMessage(), config.getMessage(),
                invalid.getMessage()}) {
            assertFalse(s.contains("Bearer"), "sin tokens en: " + s);
            assertFalse(s.contains("REPORT_API_TOKEN"), "sin secretos en: " + s);
            assertFalse(s.contains("SYBASE_PASSWORD"), "sin credenciales en: " + s);
        }
    }

    @Test
    void vetoDeVertexNoEjecutaJdbc() throws Exception {
        fake.outcome = VertexOutcome.text(VETO_JSON);
        VertexGovernor realGovernor = governor();
        Translator translator = mock(Translator.class);
        InMemoryStateStore store = new InMemoryStateStore(new OperationStateMachine());
        FixtureJdbcExecutor jdbc = new FixtureJdbcExecutor(store);
        WorkerService service = new WorkerService(mapper, new PayloadHasher(),
                new StructuralValidator(), new Catalog(mapper), realGovernor, translator,
                store, jdbc, new MockResultReporter(), 3, 5);

        ProcessingOutcome outcome = service.handleRaw(
                TestEnvelopeFactory.pushBody(mapper, TestEnvelopeFactory.validEvent("op-v-veto-e2e"),
                        "msg-veto"));

        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack(), "el veto es terminal: ACK sin reintento");
        assertTrue(store.get("op-v-veto-e2e").errorReason().contains("anula un pago"),
                "el motivo del veto debe quedar persistido para revision");
        assertEquals(0, jdbc.executeCount(), "ante veto no se ejecuta JDBC");
        verify(translator, never()).translate(any());
    }
}
