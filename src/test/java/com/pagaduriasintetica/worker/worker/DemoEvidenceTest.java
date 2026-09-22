package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.governor.MockGovernor;
import com.pagaduriasintetica.worker.translator.Translator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.HASH_OP001;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.envelopeRaw;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validData;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness E2E de demo: corre la matriz de escenarios de la prueba vertical LNB contra el
 * pipeline real (contexto Spring) y exporta la evidencia a target/demo/ vía EvidenceWriter,
 * ahora con el contrato PAYMENT_COMMITTED de Alex (CONTRACT_EVENTO_V0.1).
 *
 * Cada escenario registra en un "expediente" (Map) todo lo que pasó y {@code escenario(...)}
 * resetea StateStore/Gobernador al inicio de CADA escenario para que el override de un caso
 * (p.ej. Caso 4) nunca se filtre al siguiente.
 */
@SpringBootTest
class DemoEvidenceTest {

    private static final List<Map<String, Object>> EVIDENCIAS = new ArrayList<>(16);

    @Autowired
    ObjectMapper mapper;
    @Autowired
    InMemoryStateStore store;
    @Autowired
    MockGovernor governor;
    @Autowired
    Translator translator;
    @Autowired
    Catalog catalog;
    @Autowired
    FixtureJdbcExecutor jdbc;
    @Autowired
    MockResultReporter reporter;
    @Autowired
    WorkerService workerService;

    @BeforeEach
    void reset() {
        store.reset();
        governor.resetOverride();
        reporter.resetOverride();
        jdbc.resetOutcome();
    }

    @AfterAll
    static void exportarEvidencia() throws Exception {
        EvidenceWriter.escribir(EVIDENCIAS);
    }

    @Test
    void matrizCompletaDeEvidencia() {
        caso1_nuevoValido();             // Caso 1: pipeline completo → SUCCEEDED + pago persistido
        caso2a_duplicadoIdempotente();   // Caso 2a: reenvío idéntico → IDEMPOTENT, sin duplicar
        caso2b_inFlightRetryable();      // Caso 2b: evento "en vuelo" (PROCESSING) → NACK RETRYABLE
        caso2c_pkCollisionDlq();         // Caso 2c: mismo operationId con otra carga → DLQ (PK collision)
        caso3_prefiltroRejected();       // Caso 3: aggregateType no autorizado → REJECTED sin Vertex
        caso3b_eventTypeNoPermitido();   // Caso 3b: eventType fuera del catálogo → REJECTED
        caso4_governorAlucinaDlq();      // Caso 4: Gobernador inventa tabla → DLQ del contrato
        caso5r1_inDoubtRecuperado();     // Caso 5 Rama 1: in-doubt con hash igual → SUCCEEDED
        caso5r2_retryReprocesado();      // Caso 5 Rama 2: RETRYABLE → reproceso → SUCCEEDED
        caso5r3_inDoubtHashDistintoDlq();// Caso 5 Rama 3: hash distinto en BD → DLQ inconsistencia
        caso6_base64CorruptoDlq();       // Caso 6: Base64 inválido → DLQ
        caso6_jsonMalformadoDlq();       // Caso 6: JSON del evento roto → DLQ
        caso6_faltanCamposDlq();         // Caso 6: faltan operationId y operationData → DLQ estructural
        paso9_governorRechazado();       // Paso 9: el Gobernador rechaza por regla de negocio → REJECTED
        translation_errorColumnaInventada(); // no_invented_columns: Traductor inventa columna → TRANSLATION_ERROR
        hasher_contrato();               // Contrato: verificación pura del PAYLOAD_HASH canónico

        List<String> fallos = EVIDENCIAS.stream()
                .filter(e -> !"PASS".equals(e.get("resultado")))
                .map(e -> e.get("id") + "=" + e.get("obtenidoStatus"))
                .toList();
        assertTrue(fallos.isEmpty(), "Escenarios con FAIL: " + fallos);
    }

    // ---------------------------------------------------------------- escenarios

    private void caso1_nuevoValido() {
        Map<String, Object> ev = escenario("caso1", "Nuevo válido → SUCCEEDED",
                "Caso 1 · pasos 1-14 del contrato", "SUCCEEDED", true, true, "01-caso1-SUCCEEDED");
        try {
            PaymentCommittedEvent e = validEvent("op-001");
            String envelope = pushBody(mapper, e, "msg-001");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.operationData()));
            PlanInfo plan = plan(e);
            ev.put("gobernador", plan.governador());
            ev.put("traductor", plan.sql());
            ProcessingOutcome o = workerService.handleRaw(envelope);
            finalizar(ev, o, "SUCCEEDED", true);
            snapshot(ev, store, "op-001");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso2a_duplicadoIdempotente() {
        Map<String, Object> ev = escenario("caso2a", "Réplica idéntica → IDEMPOTENT",
                "Caso 2a", "IDEMPOTENT", true, true, "02-caso1-replica-IDEMPOTENT");
        try {
            PaymentCommittedEvent e = validEvent("op-001");
            String envelope = pushBody(mapper, e, "msg-002");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            workerService.handleRaw(envelope);                             // 1er POST → SUCCEEDED
            ProcessingOutcome o = workerService.handleRaw(envelope);       // 2do POST = el caso 2a
            ev.put("gobernador", "NO INVOCADO (respuesta inmediata por PAYLOAD_HASH idéntico)");
            ev.put("traductor", "-");
            finalizar(ev, o, "IDEMPOTENT", true);
            snapshot(ev, store, "op-001");
            ev.put("nota", "Primer POST → SUCCEEDED (mismo caso1 sobre estado limpio); este es el reenvío de Pub/Sub con la misma carga. El duplicado no re-ejecuta JDBC.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso2b_inFlightRetryable() {
        Map<String, Object> ev = escenario("caso2b", "Evento en vuelo → RETRYABLE (NACK)",
                "Caso 2b", "RETRYABLE", false, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-flight");
            String envelope = pushBody(mapper, e, "msg-flight");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            store.reserveAtomic(new OperationState("op-flight", e.eventId(), "wtr-flight", "msg-flight",
                    HASH_OP001, OperationStatus.PROCESSING, 1, null, null, null));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (evento en vuelo, NACK para redelivery)");
            ev.put("traductor", "-");
            finalizar(ev, o, "RETRYABLE", false);
            snapshot(ev, store, "op-flight");
            ev.put("nota", "simulado — registro PROCESSING pre-sembrado en StateStore; no concurrencia real de hilos");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso2c_pkCollisionDlq() {
        Map<String, Object> ev = escenario("caso2c", "Mismo operationId con otra carga → DLQ",
                "Caso 2c", "DLQ_QUARANTINED", true, false, null);
        try {
            PaymentCommittedEvent e1 = validEvent("op-001");
            workerService.handleRaw(pushBody(mapper, e1, "msg-001"));
            PaymentCommittedEvent e2 = new PaymentCommittedEvent("evt-001", "PAYMENT_COMMITTED",
                    "prizes.payment", "agg-002", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-002",
                    "op-001",
                    new OperationData("pay-002", "claim-002", "COMMITTED", "CASH",
                            new BigDecimal("199.99"), new BigDecimal("50.00"), new BigDecimal("149.99"), "USD"));
            String envelope = pushBody(mapper, e2, "msg-002");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e2));
            ev.put("payloadHash", new PayloadHasher().hash(e2.operationData()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (colisión de PK detectada en la redelivery)");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "op-001");
            ev.put("nota", "operationId reciclado con datos distintos = PK collision → cuarentena, no redelivery.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso3_prefiltroRejected() {
        Map<String, Object> ev = escenario("caso3", "AggregateType no autorizado → REJECTED (pre-filtro)",
                "Caso 3 · pre-filtro del catálogo", "REJECTED", true, true, "03-caso3-REJECTED");
        try {
            PaymentCommittedEvent e = new PaymentCommittedEvent("evt-003", "PAYMENT_COMMITTED",
                    "payroll_secret", "agg-003", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-003",
                    "op-rej-3", validData());
            String envelope = pushBody(mapper, e, "msg-003");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.operationData()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (pre-filtro del catálogo, ANTES de reservar; ver BranchingCoverageTest caso3)");
            ev.put("traductor", "-");
            finalizar(ev, o, "REJECTED", true);
            snapshot(ev, store, "op-rej-3");
            ev.put("nota", "aggregateType fuera de la whitelist: el pre-filtro del Worker rechaza sin invocar a Vertex y sin reservar operationId.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso3b_eventTypeNoPermitido() {
        Map<String, Object> ev = escenario("caso3b", "eventType no permitido → REJECTED",
                "Caso 3b", "REJECTED", true, true, "04-caso3b-REJECTED");
        try {
            PaymentCommittedEvent e = new PaymentCommittedEvent("evt-003b", "PAYMENT_CANCELLED",
                    "prizes.payment", "agg-003b", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-003b",
                    "op-rej-3b", validData());
            String envelope = pushBody(mapper, e, "msg-003b");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.operationData()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (eventType fuera de la whitelist del catálogo)");
            ev.put("traductor", "-");
            finalizar(ev, o, "REJECTED", true);
            snapshot(ev, store, "op-rej-3b");
            ev.put("nota", "El catálogo solo acepta PAYMENT_COMMITTED para prizes.payment; cualquier otro eventType se rechaza por pre-filtro.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso4_governorAlucinaDlq() {
        Map<String, Object> ev = escenario("caso4", "Gobernador alucina tabla → DLQ",
                "Caso 4 · validateGovernor", "DLQ_QUARANTINED", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-hall");
            String envelope = pushBody(mapper, e, "msg-hall");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.operationData()));
            governor.setOverride(input -> new GovernorContract(
                    "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.APPROVED,
                    input.event().operationId(), input.workerTraceId(), "Operación validada correctamente",
                    "TABLA_INVENTADA",
                    catalog.requiredFields(input.event().aggregateType()),
                    catalog.fieldMapping(input.event().aggregateType()),
                    Map.of(), Catalog.CATALOG_VERSION, List.of()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "APPROVED · target=TABLA_INVENTADA (fuera de la whitelist)");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "op-hall");
            ev.put("nota", "validateGovernor: la tabla devuelta por el LLM debe existir en el catálogo, si no → DLQ.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso5r1_inDoubtRecuperado() {
        Map<String, Object> ev = escenario("caso5-r1", "in-doubt: pago ya aplicado → SUCCEEDED",
                "Caso 5 · Rama 1", "SUCCEEDED", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-doubt");
            String envelope = pushBody(mapper, e, "msg-doubt");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            store.seedDoubtful("op-doubt", HASH_OP001, HASH_OP001, "SYNTHETIC_PAYMENTS",
                    List.<Object>of("pay-001", "claim-001", "APROBADO", "EFECTIVO", "200.00", "50.00", "150.00", "USD",
                            "op-doubt", "wtr-1", "evt-001", HASH_OP001));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (in-doubt recuperado por PAYLOAD_HASH idéntico)");
            ev.put("traductor", "-");
            finalizar(ev, o, "SUCCEEDED", true);
            snapshot(ev, store, "op-doubt");
            ev.put("nota", "simulado: fila en SYNTHETIC_PAYMENTS + estado PROCESSING pre-sembrados; el Worker promueve a SUCCEEDED sin reescribir (commit ya aplicado).");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso5r2_retryReprocesado() {
        Map<String, Object> ev = escenario("caso5-r2", "Retry (RETRYABLE) reprocesado → SUCCEEDED",
                "Caso 5 · Rama 2", "SUCCEEDED", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-retry");
            String envelope = pushBody(mapper, e, "msg-retry");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            store.reserveAtomic(new OperationState("op-retry", e.eventId(), "wtr-retry", "msg-retry",
                    HASH_OP001, OperationStatus.PROCESSING, 1, null, null, null));
            store.update(store.get("op-retry").withStatus(OperationStatus.RETRYABLE));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            PlanInfo plan = plan(e);
            ev.put("gobernador", plan.governador());
            ev.put("traductor", plan.sql());
            finalizar(ev, o, "SUCCEEDED", true);
            snapshot(ev, store, "op-retry");
            ev.put("nota", "redelivery recibe RETRYABLE sin commit previo → reproceso completo → SUCCEEDED + pago.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso5r3_inDoubtHashDistintoDlq() {
        Map<String, Object> ev = escenario("caso5-r3", "in-doubt con otro hash → DLQ",
                "Caso 5 · Rama 3", "DLQ_QUARANTINED", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-bad");
            String envelope = pushBody(mapper, e, "msg-bad");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            store.seedInDoubt("op-bad", HASH_OP001, "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                    "SYNTHETIC_PAYMENTS", List.<Object>of("x"));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (inconsistencia de PAYLOAD_HASH detectada en StateStore)");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "op-bad");
            ev.put("nota", "fila de pago existente con PAYLOAD_HASH distinto = inconsistencia de BD → cuarentena.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso6_base64CorruptoDlq() {
        Map<String, Object> ev = escenario("caso6-b64", "Base64 corrupto → DLQ",
                "Caso 6", "DLQ_QUARANTINED", true, true, "05-caso6-b64-DLQ");
        try {
            String envelope = envelopeRaw(mapper, "!!not-valid-base64!!", "msg-b64");
            ev.put("envelopeJson", envelope);
            ev.put("evento", "-");
            ev.put("payloadHash", "-");
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "-");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso6_jsonMalformadoDlq() {
        Map<String, Object> ev = escenario("caso6-json", "JSON del evento malformado → DLQ",
                "Caso 6", "DLQ_QUARANTINED", true, true, "06-caso6-json-DLQ");
        try {
            String envelope = envelopeRaw(mapper, Base64.getEncoder().encodeToString("{".getBytes()), "msg-json");
            ev.put("envelopeJson", envelope);
            ev.put("evento", "-");
            ev.put("payloadHash", "-");
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "-");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso6_faltanCamposDlq() {
        Map<String, Object> ev = escenario("caso6-faltan", "Faltan operationId y operationData → DLQ",
                "Caso 6", "DLQ_QUARANTINED", true, true, "07-caso6-faltan-DLQ");
        try {
            PaymentCommittedEvent e = new PaymentCommittedEvent("evt-006", "PAYMENT_COMMITTED",
                    "prizes.payment", "agg-006", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-006",
                    null, null);
            String envelope = pushBody(mapper, e, "msg-faltan");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", "-");
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "-");
            ev.put("nota", "Fallo de esquema ANTES de tocar la reserva atómica → DLQ estructural.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void paso9_governorRechazado() {
        Map<String, Object> ev = escenario("paso9", "Gobernador rechaza por regla de negocio → REJECTED",
                "Paso 9 del contrato", "REJECTED", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-rej-9");
            String envelope = pushBody(mapper, e, "msg-rej9");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.operationData()));
            governor.setOverride(input -> new GovernorContract(
                    "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.REJECTED,
                    input.event().operationId(), input.workerTraceId(),
                    "Regla de negocio: límite de monto excedido",
                    "SYNTHETIC_PAYMENTS",
                    catalog.requiredFields(input.event().aggregateType()),
                    catalog.fieldMapping(input.event().aggregateType()),
                    Map.of(), Catalog.CATALOG_VERSION, List.of()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "REJECTED · reason=Regla de negocio: límite de monto excedido");
            ev.put("traductor", "-");
            finalizar(ev, o, "REJECTED", true);
            snapshot(ev, store, "op-rej-9");
            ev.put("nota", "El rechazo del Gobernador (paso 9) es distinto del pre-filtro del Worker (Caso 3): aquí Vertex sí fue invocado, y el resultado REJECTED NO va a DLQ.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void translation_errorColumnaInventada() {
        Map<String, Object> ev = escenario("translation", "Traductor inventa columna → TRANSLATION_ERROR",
                "Regla no_invented_columns", "TRANSLATION_ERROR", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-xtr");
            String envelope = pushBody(mapper, e, "msg-xtr");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);

            JsonMapper jm = JsonMapper.builder().build();
            Catalog cat = new Catalog(jm);
            InMemoryStateStore isolado = new InMemoryStateStore(new OperationStateMachine());
            Governor gov = mock(Governor.class);
            Translator tr = mock(Translator.class);
            when(gov.decide(any(GovernorInput.class))).thenReturn(new GovernorContract(
                    "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.APPROVED,
                    e.operationId(), "wtr-xtr", "Operación validada correctamente",
                    "SYNTHETIC_PAYMENTS", cat.requiredFields(e.aggregateType()),
                    cat.fieldMapping(e.aggregateType()),
                    valueRulesObject(cat, e.aggregateType()),
                    Catalog.CATALOG_VERSION, List.of()));
            String inventado = "INSERT INTO SYNTHETIC_PAYMENTS (ID_PAGO, COD_RECLAMO, ESTADO, MEDIO_PAGO, "
                    + "MONTO_BRUTO, MONTO_RETENCION, MONTO_NETO, MONEDA, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH, COLUMNA_INVENTADA) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
            when(tr.translate(any(TranslatorInput.class))).thenReturn(
                    new TranslatorResult("TRANSLATED", inventado,
                            List.of("pay-001", "claim-001", "APROBADO", "EFECTIVO", "200.00", "50.00", "150.00", "USD",
                                    "op-xtr", "wtr-xtr", "evt-001", HASH_OP001, "xyz"),
                            inventado));
            WorkerService iso = new WorkerService(jm, new PayloadHasher(), new StructuralValidator(),
                    cat, gov, tr, isolado, new FixtureJdbcExecutor(isolado), new MockResultReporter());

            ProcessingOutcome o = iso.handleRaw(envelope);
            ev.put("gobernador", "APPROVED · plan válido");
            ev.put("traductor", "sql_template con COLUMNA_INVENTADA (mock aislado; el DeterministicTranslator real no la produce)");
            finalizar(ev, o, "TRANSLATION_ERROR", true);
            snapshot(ev, isolado, "op-xtr");
            ev.put("nota", "Guard validateTranslation: tabla exacta, 12 parámetros y columnas ⊆ whitelist.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void hasher_contrato() {
        Map<String, Object> ev = escenario("hasher", "PAYLOAD_HASH canónico del contrato",
                "Contrato §2.1", "PASS", true, false, null);
        try {
            PaymentCommittedEvent e = validEvent("op-hash");
            String hash = new PayloadHasher().hash(e.operationData());
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            boolean ok = HASH_OP001.equals(hash);
            ev.put("resultado", ok ? "PASS" : "FAIL");
            ev.put("obtenidoStatus", hash);
            ev.put("esperadoStatus", "0fd5240a…246a8 (contrato PAYMENT_COMMITTED V0.1)");
            ev.put("httpTexto", "N/A (función pura, sin HTTP)");
            if (!ok) {
                ev.put("detalle", "hash != " + HASH_OP001);
            }
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> escenario(String id, String titulo, String casoRef, String esperado,
                                          boolean ackEsperado, boolean liveSafe, String liveName) {
        store.reset();
        governor.resetOverride();
        reporter.resetOverride();
        jdbc.resetOutcome();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", id);
        ev.put("titulo", titulo);
        ev.put("casoRef", casoRef);
        ev.put("esperadoStatus", esperado);
        ev.put("ackEsperado", ackEsperado);
        ev.put("liveSafe", liveSafe);
        if (liveSafe) {
            ev.put("liveName", liveName);
        }
        EVIDENCIAS.add(ev);
        return ev;
    }

    private void finalizar(Map<String, Object> ev, ProcessingOutcome o, String esperado, boolean ackEsperado) {
        ev.put("obtenidoStatus", o.status().name());
        ev.put("httpTexto", (o.ack() ? "200" : "500") + " (ack=" + o.ack() + ")");
        boolean ok = o.status().name().equals(esperado) && o.ack() == ackEsperado;
        ev.put("resultado", ok ? "PASS" : "FAIL");
        if (!ok) {
            ev.put("detalle", "esperado " + esperado + " ack=" + ackEsperado
                    + "; obtenido " + o.status() + " ack=" + o.ack());
        }
    }

    private void fallo(Map<String, Object> ev, Exception ex) {
        ev.put("resultado", "FAIL");
        ev.put("obtenidoStatus", "EXCEPTION");
        ev.put("httpTexto", "-");
        ev.put("detalle", ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    private void snapshot(Map<String, Object> ev, InMemoryStateStore st, String operationId) {
        OperationState s = st.get(operationId);
        ev.put("estadoFinal", s == null ? "-" : estadoFila(s));
        PaymentRow row = st.findPayment(operationId);
        ev.put("paymentPersisted", row != null);
        ev.put("cuarentena", st.quarantineAudit());
    }

    private String estadoFila(OperationState s) {
        return "status=" + s.status()
                + " decision=" + (s.governorDecision() == null ? "-" : s.governorDecision())
                + " target=" + (s.targetTable() == null ? "-" : s.targetTable())
                + " attempt=" + s.attemptCount()
                + (s.errorReason() == null ? "" : " error=" + s.errorReason());
    }

    private Map<String, Object> evento(PaymentCommittedEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", e.eventId());
        m.put("event_type", e.eventType());
        m.put("aggregate_type", e.aggregateType());
        m.put("aggregate_id", e.aggregateId());
        m.put("event_version", e.eventVersion());
        m.put("destination_system", e.destinationSystem());
        m.put("occurred_at", e.occurredAt());
        m.put("correlation_id", e.correlationId());
        m.put("operationId", e.operationId());
        if (e.operationData() != null) {
            OperationData d = e.operationData();
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("payment_id", d.paymentId());
            p.put("claim_id", d.claimId());
            p.put("status", d.status());
            p.put("payment_method", d.paymentMethod());
            p.put("gross_amount", String.valueOf(d.grossAmount()));
            p.put("withholding_amount", String.valueOf(d.withholdingAmount()));
            p.put("net_amount", String.valueOf(d.netAmount()));
            p.put("currency", d.currency());
            m.put("operationData", p);
        } else {
            m.put("operationData", null);
        }
        return m;
    }

    private PlanInfo plan(PaymentCommittedEvent e) throws Exception {
        String hash = new PayloadHasher().hash(e.operationData());
        GovernorContract g = governor.decide(new GovernorInput(e, "wtr-plan"));
        if (g.decision() != GovernorDecision.APPROVED) {
            return new PlanInfo("REJECTED · " + g.reason(), "-");
        }
        TranslatorResult r = translator.translate(new TranslatorInput(e, g, hash, "wtr-plan"));
        return new PlanInfo("APPROVED · target=" + g.target_table(), r.sql_template());
    }

    private record PlanInfo(String governador, String sql) {
    }

    private static Map<String, Object> valueRulesObject(Catalog cat, String aggregateType) {
        Map<String, Object> out = new LinkedHashMap<>();
        cat.valueRules(aggregateType).forEach(out::put);
        return out;
    }
}