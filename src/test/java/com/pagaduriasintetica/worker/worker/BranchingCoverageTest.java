package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.SyntheticPayload;
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
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Ramas de decisión de LNB verificadas por comportamiento (Mockito puro, sin ApplicationContext):
// Caso 3 (pre-filtro del catálogo sin invocar a Vertex), paso 9 (rechazo del Gobernador) y
// TRANSLATION_ERROR (columna inventada por el Traductor).
class BranchingCoverageTest {

    private ObjectMapper mapper;
    private InMemoryStateStore store;
    private Governor governor;
    private Translator translator;
    private WorkerService service;

    @BeforeEach
    void setUp() throws Exception {
        mapper = JsonMapper.builder().build();            // Jackson 3: mapper independiente del contexto
        store = new InMemoryStateStore();                 // StateStore vacío por test (aislamiento)
        governor = mock(Governor.class);                  // Gobernador mock: nosotros decidimos su salida
        translator = mock(Translator.class);              // Traductor mock: solo se usa si el flujo lo llama
        service = new WorkerService(mapper, new PayloadHasher(), new StructuralValidator(),
                new Catalog(mapper), governor, translator, store);  // pipeline completo armado a mano
    }

    ProcessingOutcome handle(SyntheticEvent event) throws Exception {
        // Envía el evento por un WorkerService construido a mano (sin Spring) con mocks de
        // Gobernador y Traductor, para poder verificar "quién fue invocado" (verify/never).
        return service.handleRaw(pushBody(mapper, event, "msg-x"));
    }

    @Test
    // Caso 3 — el PRE-FILTRO del catálogo rechaza la entidad parasitada (payroll_secret) SIN
    // invocar a Vertex (governor) ni al traductor: verificado por comportamiento, no solo por
    // el estado final REJECTED. Esto es la garantía anti-costo/anti-latencia del diseño.
    void caso3_prefiltroRechazaSinInvocarGobernadorNiTraductor() throws Exception {
        SyntheticEvent unauthorized = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-003",
                "SYNTHETIC_PAYMENT_REQUESTED", "op-rej-3", "trace-003", "2026-09-07T00:00:00Z",
                "INSERT", "payroll_secret", new SyntheticPayload("c1", new BigDecimal("100"), "2026-09-07", "B"));

        ProcessingOutcome outcome = handle(unauthorized);

        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.REJECTED, store.get("op-rej-3").status());
        assertEquals(0, store.paymentCount("op-rej-3"));
        verify(governor, never()).decide(any(SyntheticEvent.class));
        verify(translator, never()).translate(any(), any(), any());
    }

    @Test
    // Paso 9 — el PROPIO Gobernador rechaza por regla de negocio → REJECTED (estado de negocio,
    // NO violación de contrato). Victoria: se verifica que el Gobernador SÍ fue llamado y el
    // Traductor nunca — distingue rechazo de negocio vs alucinación/contrato.
    void caso9_gobernadorRechazaPorReglaDeNegocio() throws Exception {
        when(governor.decide(any(SyntheticEvent.class))).thenReturn(new GovernorContract(
                "CONTRACT_SYNTHETIC_V0", GovernorDecision.REJECTED, "op-rej-9", "trace-001",
                "Rechazo por regla de negocio", null, null, null, null, "CATALOG_SYNTHETIC_V0", List.of()));

        ProcessingOutcome outcome = handle(validEvent("op-rej-9"));

        assertEquals(OperationStatus.REJECTED, outcome.status(), "rechazo de negocio, no violación de contrato");
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.REJECTED, store.get("op-rej-9").status());
        assertEquals(0, store.paymentCount("op-rej-9"));
        verify(governor).decide(any(SyntheticEvent.class));
        verify(translator, never()).translate(any(), any(), any());
    }

    @Test
    // Regla no_invented_columns — el Traductor devuelve una columna libre (COLUMNA_INVENTADA):
    // el guard validateTranslation debe tiparlo como TRANSLATION_ERROR (estado propio, no DLQ).
    void caso_translatorDevuelveColumnaInventadaEsTranslationError() throws Exception {
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("claimId", "COD_RECLAMO");
        fieldMapping.put("amount", "MONTO");
        fieldMapping.put("operationDate", "FECHA_OPER");
        fieldMapping.put("beneficiary", "BENEFICIARIO");
        when(governor.decide(any(SyntheticEvent.class))).thenReturn(new GovernorContract(
                "CONTRACT_SYNTHETIC_V0", GovernorDecision.APPROVED, "op-xtr", "trace-001",
                null, "SYNTHETIC_PAYMENTS", null, fieldMapping, null, "CATALOG_SYNTHETIC_V0", List.of()));
        // El sql_template trae una columna fuera de field_mapping + técnicas → debe ser TRANSLATION_ERROR.
        when(translator.translate(any(), any(), any())).thenReturn(new TranslatorResult("TRANSLATED",
                "INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH, COLUMNA_INVENTADA) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                List.of("c1", new BigDecimal("150.00"), "2026-09-07", "Ana Gomez", "op-xtr", "trace-001", "evt-001", "hash"),
                "INSERT INTO SYNTHETIC_PAYMENTS (...)"));

        ProcessingOutcome outcome = handle(validEvent("op-xtr"));

        assertEquals(OperationStatus.TRANSLATION_ERROR, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.TRANSLATION_ERROR, store.get("op-xtr").status());
        assertEquals(0, store.paymentCount("op-xtr"));
    }
}