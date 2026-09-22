package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.translator.Translator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validData;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Ramas de decisión de LNB verificadas por comportamiento (Mockito puro, sin ApplicationContext):
// pre-filtro del catálogo (sin invocar al Gobernador/Traductor y ANTES de reservar), invariante
// monetaria (se evalúa antes del Gobernador), rechazo del Gobernador y TRANSLATION_ERROR
// (columna inventada por el Traductor).
class BranchingCoverageTest {

    private ObjectMapper mapper;
    private InMemoryStateStore store;
    private Governor governor;
    private Translator translator;
    private FixtureJdbcExecutor jdbc;
    private MockResultReporter reporter;
    private WorkerService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = JsonMapper.builder().build();            // Jackson 3: mapper independiente del contexto
        store = new InMemoryStateStore(new OperationStateMachine()); // StateStore vacío por test
        jdbc = new FixtureJdbcExecutor(store);            // JdbcExecutor real (fixture)
        reporter = new MockResultReporter();              // ResultReporter mock por defecto OK
        governor = mock(Governor.class);                  // Gobernador mock: nosotros decidimos su salida
        translator = mock(Translator.class);              // Traductor mock: solo se usa si el flujo lo llama
        service = new WorkerService(mapper, new PayloadHasher(), new StructuralValidator(),
                new Catalog(mapper), governor, translator, store, jdbc, reporter);
    }

    ProcessingOutcome handle(PaymentCommittedEvent event) throws Exception {
        return service.handleRaw(pushBody(mapper, event, "msg-x"));
    }

    // Plan Fase 5, prueba 2: aggregateType fuera del catálogo -> REJECTED por el pre-filtro,
    // SIN invocar al Gobernador ni al Traductor y ANTES de reservar operationId (no hay fila).
    @Test
    void caso3_prefiltroRechazaSinInvocarGobernadorNiTraductorNiReservar() throws Exception {
        PaymentCommittedEvent unauthorized = new PaymentCommittedEvent("evt-003", "PAYMENT_COMMITTED",
                "payroll_secret", "agg-003", 1, "SYBASE", "2026-09-07T00:00:00Z",
                "corr-003", "op-rej-3", validData());

        ProcessingOutcome outcome = handle(unauthorized);

        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack());
        assertNull(store.get("op-rej-3"), "pre-filtro ANTES de reservar -> no debe existir fila");
        assertEquals(0, store.paymentCount("op-rej-3"));
        verify(governor, never()).decide(any(GovernorInput.class));
        verify(translator, never()).translate(any(TranslatorInput.class));
    }

    // Plan Fase 5, prueba 7: invariante monetaria (validationRules) incumplida -> REJECTED y
    // NUNCA se invoca al Gobernador (la regla es determinista, no depende del LLM).
    @Test
    void invarianteMonetariaVioladaRejectedAntesDelGobernador() throws Exception {
        OperationData data = new OperationData("pay-001", "claim-001", "COMMITTED", "CASH",
                new BigDecimal("200.00"), new BigDecimal("50.00"), new BigDecimal("100.00"), "USD");
        PaymentCommittedEvent bad = new PaymentCommittedEvent("evt-007", "PAYMENT_COMMITTED",
                "prizes.payment", "agg-007", 1, "SYBASE", "2026-09-07T00:00:00Z",
                "corr-007", "op-inv", data);

        ProcessingOutcome outcome = handle(bad);

        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.REJECTED, store.get("op-inv").status());
        verify(governor, never()).decide(any(GovernorInput.class));
        verify(translator, never()).translate(any(TranslatorInput.class));
    }

    // Paso 9 — el PROPIO Gobernador rechaza por regla de negocio -> REJECTED (estado de negocio,
    // NO violación de contrato). Victoria: se verifica que el Gobernador SÍ fue llamado y el
    // Traductor nunca — distingue rechazo de negocio vs alucinación/contrato.
    @Test
    void caso9_gobernadorRechazaPorReglaDeNegocio() throws Exception {
        whenGovernorRejects();

        ProcessingOutcome outcome = handle(validEvent("op-rej-9"));

        assertEquals(OperationStatus.REJECTED, outcome.status(), "rechazo de negocio, no violación de contrato");
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.REJECTED, store.get("op-rej-9").status());
        assertEquals(0, store.paymentCount("op-rej-9"));
        verify(governor).decide(any(GovernorInput.class));
        verify(translator, never()).translate(any(TranslatorInput.class));
    }

    // Regla no_invented_columns — el Traductor devuelve una columna libre: el guard
    // validateTranslation debe tiparlo como TRANSLATION_ERROR (estado propio, no DLQ), y el
    // JDBC no debe ejecutarse.
    @Test
    void caso_translatorDevuelveColumnaInventadaEsTranslationError() throws Exception {
        whenGovernorApproves("op-xtr");
        String sqlInventado = "INSERT INTO SYNTHETIC_PAYMENTS (ID_PAGO, COD_RECLAMO, ESTADO, MEDIO_PAGO, "
                + "MONTO_BRUTO, MONTO_RETENCION, MONTO_NETO, MONEDA, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH, COLUMNA_INVENTADA) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        when(translator.translate(any(TranslatorInput.class))).thenReturn(new TranslatorResult("TRANSLATED",
                sqlInventado, List.of("pay-001", "claim-001", "APROBADO", "EFECTIVO",
                "200.00", "50.00", "150.00", "USD", "op-xtr", "wtr-1", "evt-001", "hash", "xyz"),
                "INSERT INTO SYNTHETIC_PAYMENTS (...)"));

        ProcessingOutcome outcome = handle(validEvent("op-xtr"));

        assertEquals(OperationStatus.TRANSLATION_ERROR, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.TRANSLATION_ERROR, store.get("op-xtr").status());
        assertEquals(0, store.paymentCount("op-xtr"));
        assertEquals(0, jdbc.executeCount(), "el JDBC no debe ejecutarse con un plan fuera de whitelist");
        verify(translator).translate(any(TranslatorInput.class));
    }

    private void whenGovernorRejects() {
        when(governor.decide(any(GovernorInput.class))).thenReturn(new GovernorContract(
                "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.REJECTED, "op-rej-9", "wtr-9",
                "Rechazo por regla de negocio", null, null, null, Map.of(), Catalog.CATALOG_VERSION, List.of()));
    }

    private void whenGovernorApproves(String operationId) {
        String aggregateType = "prizes.payment";
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("paymentId", "ID_PAGO");
        fieldMapping.put("claimId", "COD_RECLAMO");
        fieldMapping.put("status", "ESTADO");
        fieldMapping.put("paymentMethod", "MEDIO_PAGO");
        fieldMapping.put("grossAmount", "MONTO_BRUTO");
        fieldMapping.put("withholdingAmount", "MONTO_RETENCION");
        fieldMapping.put("netAmount", "MONTO_NETO");
        fieldMapping.put("currency", "MONEDA");
        Map<String, Object> valueRules = new LinkedHashMap<>();
        valueRules.put("status.COMMITTED", "APROBADO");
        valueRules.put("paymentMethod.CASH", "EFECTIVO");
        valueRules.put("paymentMethod.TRANSFER", "TRANSFERENCIA");
        valueRules.put("currency.USD", "USD");
        when(governor.decide(any(GovernorInput.class))).thenReturn(new GovernorContract(
                "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.APPROVED, operationId, "wtr-1",
                "Operación validada correctamente", "SYNTHETIC_PAYMENTS",
                List.of("paymentId", "claimId", "status", "paymentMethod", "grossAmount",
                        "withholdingAmount", "netAmount", "currency"),
                fieldMapping, valueRules, Catalog.CATALOG_VERSION, List.of()));
    }
}