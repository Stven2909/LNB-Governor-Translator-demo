package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.config.WorkerProperties;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorEnrichmentStatus;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.worker.TestEnvelopeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scaffold de clasificacion de VertexGovernor con cliente fake (sin red ni credenciales).
 * Solo cubre lo NO bloqueado por JD: el mapeo transporte/respuesta -> contrato degradado o
 * excepcion tipada, con la decision deterministica intacta. La matriz de veto fail-closed
 * (D x V) y los terminales de agotamiento quedan pendientes de Q1/Q2.
 */
class VertexGovernorTest {

    private static final String VALID_JSON = """
            {"summary":"Operacion consistente.","observations":["monto dentro de rango"],
             "suggested_mapping":{"status":"ESTADO"}}""";

    private ObjectMapper mapper;
    private DeterministicGovernorSupport support;
    private WorkerProperties.Vertex props;
    private FakeClient fake;

    /** Fake programable del adaptador Vertex: devuelve un outcome o lanza la falla indicada. */
    private static final class FakeClient implements VertexClient {
        VertexOutcome outcome = VertexOutcome.text(VALID_JSON);
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
    void enrichmentValidoSeAdjuntaConDecisionDeterministica() throws Exception {
        GovernorContract contract = governor().decide(input("op-v-attached"));

        assertEquals(GovernorDecision.APPROVED, contract.decision());
        assertEquals(GovernorEnrichmentStatus.ATTACHED, contract.enrichmentStatus());
        assertEquals("Operacion consistente.", contract.enrichment().summary());
        assertEquals("ESTADO", contract.enrichment().suggestedMapping().get("status"));
    }

    @Test
    void schemaVioladoDegradaConCodigoDistinguible() throws Exception {
        fake.outcome = VertexOutcome.text("{\"observations\":[]}");

        GovernorContract contract = governor().decide(input("op-v-schema"));

        assertEquals(GovernorDecision.APPROVED, contract.decision());
        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_SCHEMA_VIOLATION,
                contract.enrichment().unavailableReason());
    }

    @Test
    void jsonMalformadoDegradaComoInvalid() throws Exception {
        fake.outcome = VertexOutcome.text("{no-es-json");

        GovernorContract contract = governor().decide(input("op-v-invalid"));

        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_INVALID, contract.enrichment().unavailableReason());
    }

    @Test
    void safetyBlockNoSeConfundeConTruncado() throws Exception {
        fake.outcome = VertexOutcome.blocked("SAFETY");

        GovernorContract contract = governor().decide(input("op-v-safety"));

        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_SAFETY_BLOCK, contract.enrichment().unavailableReason());
        assertFalse(contract.enrichment().unavailableReason().contains("TRUNCATED"),
                "un bloqueo de seguridad nunca debe clasificarse como truncado");
    }

    @Test
    void maxTokensDegradaComoTruncadoYNoComoSafety() throws Exception {
        fake.outcome = VertexOutcome.truncated("MAX_TOKENS");

        GovernorContract contract = governor().decide(input("op-v-trunc"));

        assertEquals(GovernorDecision.APPROVED, contract.decision());
        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_TRUNCATED, contract.enrichment().unavailableReason());
        assertFalse(contract.enrichment().unavailableReason().contains("SAFETY"),
                "MAX_TOKENS es truncado, no un bloqueo de seguridad");
    }

    @Test
    void textoVacioDegradaComoEmpty() throws Exception {
        fake.outcome = VertexOutcome.text("   ");

        GovernorContract contract = governor().decide(input("op-v-empty"));

        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_EMPTY, contract.enrichment().unavailableReason());
    }

    @Test
    void outcomeNuloDegradaComoSafety() throws Exception {
        fake.outcome = null;

        GovernorContract contract = governor().decide(input("op-v-null"));

        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_SAFETY_BLOCK, contract.enrichment().unavailableReason());
    }

    @Test
    void fallosDeConfiguracionLanzanGovernorConfigurationException() throws Exception {
        for (int code : new int[]{401, 403, 404}) {
            fake.failure = new VertexCallException(code, "config", "falla-" + code, null);
            GovernorInput in = input("op-v-cfg-" + code);

            GovernorConfigurationException thrown = assertThrows(
                    GovernorConfigurationException.class, () -> governor().decide(in),
                    "codigo " + code + " debe ser fallo de configuracion");
            assertTrue(thrown.getMessage().contains(String.valueOf(code)));
        }
    }

    @Test
    void transitoriosDegradanConCodigoDistinguible() throws Exception {
        int[] codes = {408, 429, 500, 503, -1};
        String[] expected = {"VERTEX_UNAVAILABLE_TIMEOUT_408", "VERTEX_UNAVAILABLE_QUOTA_429",
                "VERTEX_UNAVAILABLE_5XX", "VERTEX_UNAVAILABLE_5XX", "VERTEX_UNAVAILABLE_TIMEOUT"};
        for (int i = 0; i < codes.length; i++) {
            fake.outcome = VertexOutcome.text(VALID_JSON);
            fake.failure = new VertexCallException(codes[i], "t", "falla", null);

            GovernorContract contract = governor().decide(input("op-v-t-" + codes[i]));

            assertEquals(GovernorDecision.APPROVED, contract.decision());
            assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
            assertEquals(expected[i], contract.enrichment().unavailableReason(),
                    "codigo " + codes[i] + " debe distinguirse");
        }
    }

    @Test
    void excepcionNoPrevistaDelClienteDegradaComoUnexpected() throws Exception {
        fake.failure = new IllegalStateException("falla inesperada del adaptador");

        GovernorContract contract = governor().decide(input("op-v-unexpected"));

        assertEquals(GovernorEnrichmentStatus.UNAVAILABLE, contract.enrichmentStatus());
        assertEquals(VertexGovernor.CODE_UNEXPECTED, contract.enrichment().unavailableReason());
    }

    @Test
    void motivosYMensajesNoExponenSecretos() throws Exception {
        fake.failure = new VertexCallException(503, "unavailable", "cuota excedida", null);
        GovernorContract degraded = governor().decide(input("op-v-sanit"));

        for (String s : new String[]{degraded.enrichment().unavailableReason(),
                degraded.reason(), degraded.contract_version(), degraded.catalog_version()}) {
            assertFalse(s.contains("Bearer"), "sin tokens en: " + s);
            assertFalse(s.contains("REPORT_API_TOKEN"), "sin referencias de secretos en: " + s);
            assertFalse(s.contains("SYBASE_PASSWORD"), "sin credenciales en: " + s);
        }

        fake.failure = new VertexCallException(403, "denied", "permiso denegado", null);
        GovernorConfigurationException thrown = assertThrows(GovernorConfigurationException.class,
                () -> governor().decide(input("op-v-sanit-cfg")));
        assertFalse(thrown.getMessage().contains("Bearer"));
        assertFalse(thrown.getMessage().contains("REPORT_API_TOKEN"));
        assertFalse(thrown.getMessage().contains("SYBASE_PASSWORD"));
    }
}
