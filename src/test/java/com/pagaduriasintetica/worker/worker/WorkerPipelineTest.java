package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.JdbcOutcome;
import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.ReportResult;
import com.pagaduriasintetica.worker.governor.MockGovernor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.HASH_OP001;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.envelopeRaw;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validData;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Suite E2E de la prueba vertical (contexto Spring, beans reales): los 6 casos de LNB +
// subcasos de in-doubt y las ~15 pruebas nuevas del plan congelado (Fase 5), cubriendo
// eventVersion, aggregateType/eventType, destinationSystem, montos, invariante monetaria,
// colisión de PK, JDBC UNKNOWN, fallo/409 del reporte y redelivery sin segunda ejecución JDBC.
@SpringBootTest
class WorkerPipelineTest {

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
        // executeCount() es acumulativo (bean singleton compartido en el contexto Spring): se
        // mide el DELTA respecto a esta baseline capturada al inicio de CADA caso.
        jdbcBaseline = jdbc.executeCount();
    }

    private int jdbcDelta() {
        return jdbc.executeCount() - jdbcBaseline;
    }

    ProcessingOutcome handle(PaymentCommittedEvent event) throws Exception {
        return workerService.handleRaw(pushBody(mapper, event, "msg-x"));
    }

    // Caso 1 — pipeline completo: reserva → Gobernador → Traductor → JDBC → reporte → ACK SUCCEEDED.
    @Test
    void caso1_nuevoValidoLlegaASucceeded() throws Exception {
        ProcessingOutcome outcome = handle(validEvent("op-001"));
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-001").status());
        assertNotNull(store.findPayment("op-001"));
        assertEquals(HASH_OP001, store.findPayment("op-001").payloadHash());
        assertEquals(1, jdbcDelta(), "exactamente una ejecución JDBC");
    }

    // Caso 2a — reenvío idéntico (mismo hash canónico) → IDEMPOTENT, sin segundo insert ni JDBC.
    @Test
    void caso2a_duplicadoMismoHashEsIdempotent() throws Exception {
        handle(validEvent("op-001"));
        ProcessingOutcome outcome = handle(validEvent("op-001"));
        assertEquals(OperationStatus.IDEMPOTENT, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(1, store.paymentCount("op-001"), "no debe duplicarse la inserción");
        assertEquals(1, jdbcDelta(), "el duplicado no re-ejecuta JDBC");
    }

    // Caso 2b — evento ya "en vuelo" (PROCESSING, sin fila) → NACK RETRYABLE (redelivery).
    @Test
    void caso2b_eventoEnVueloRespuestaNack() throws Exception {
        PaymentCommittedEvent flight = validEvent("op-flight");
        store.reserveAtomic(new OperationState("op-flight", "evt-001", "wtr-flight", "msg-flight",
                HASH_OP001, OperationStatus.PROCESSING, 1, null, null, null));
        ProcessingOutcome outcome = handle(flight);
        assertFalse(outcome.ack(), "respuesta NACK para redelivery programada");
        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertEquals(0, store.paymentCount("op-flight"));
    }

    // Caso 2c — mismo operationId pero distinto payload = PK collision → ACK DLQ.
    @Test
    void caso2c_operationIdRecicladoConHashDiferenteEsDlq() throws Exception {
        handle(validEvent("op-001"));
        PaymentCommittedEvent differentPayload = new PaymentCommittedEvent(
                "evt-001", "PAYMENT_COMMITTED", "prizes.payment", "agg-002", 1,
                "SYBASE", "2026-09-07T00:00:00Z", "corr-002", "op-001",
                new OperationData("pay-002", "claim-002", "COMMITTED", "CASH",
                        new BigDecimal("199.99"), new BigDecimal("50.00"), new BigDecimal("149.99"), "USD"));
        ProcessingOutcome outcome = handle(differentPayload);
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack());
        // El operationId ya constaba SUCCEEDED (terminal): el ACK hacia Pub/Sub es DLQ para el
        // mensaje conflictivo, pero el estado ganador NO se reescribe (no se corrompe la fila).
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-001").status(),
                "un SUCCEEDED terminal no se degrada a DLQ");
        assertEquals(1, store.paymentCount("op-001"));
    }

    // Plan F5 prueba 2 — aggregateType fuera de la whitelist → REJECTED por pre-filtro (sin fila).
    @Test
    void caso3_aggregateTypeFueraDelCatalogoEsRejected() throws Exception {
        PaymentCommittedEvent unauthorized = new PaymentCommittedEvent("evt-003", "PAYMENT_COMMITTED",
                "payroll_secret", "agg-003", 1, "SYBASE", "2026-09-07T00:00:00Z",
                "corr-003", "op-rej", validData());
        ProcessingOutcome outcome = handle(unauthorized);
        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack());
        assertNull(store.get("op-rej"), "pre-filtro ANTES de reservar");
        assertEquals(0, store.paymentCount("op-rej"));
    }

    // Plan F5 prueba 3 — eventType != PAYMENT_COMMITTED → REJECTED por pre-filtro del catálogo.
    @Test
    void caso3b_eventTypeFueraDelCatalogoEsRejected() throws Exception {
        PaymentCommittedEvent badType = new PaymentCommittedEvent("evt-003b", "PAYMENT_CANCELLED",
                "prizes.payment", "agg-003b", 1, "SYBASE", "2026-09-07T00:00:00Z",
                "corr-003b", "op-rej-2", validData());
        assertEquals(OperationStatus.REJECTED, handle(badType).status());
    }

    // Caso 4 — el Gobernador "alucina" una tabla que no está en el catálogo → DLQ.
    @Test
    void caso4_alucinacionDelGobernadorEsDlq() throws Exception {
        governor.setOverride(input -> new GovernorContract("CONTRACT_PAYMENT_COMMITTED_V0.1",
                GovernorDecision.APPROVED, input.event().operationId(), input.workerTraceId(),
                "hallucinated", "TABLA_INVENTADA", null, null, Map.of(), Catalog.CATALOG_VERSION, List.of()));
        ProcessingOutcome outcome = handle(validEvent("op-hall"));
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.reason().contains("Governor output violated catalog whitelist"));
        assertEquals(0, store.paymentCount("op-hall"));
    }

    // Caso 5 Rama 1 — in-doubt (PROCESSING + fila persistida): se promueve a SUCCEEDED sin reinsertar.
    @Test
    void caso5_rama1_inDoubtPromueveASucceededSinReinsertar() throws Exception {
        store.seedDoubtful("op-doubt", HASH_OP001, HASH_OP001, "SYNTHETIC_PAYMENTS", List.of());
        ProcessingOutcome outcome = handle(validEvent("op-doubt"));
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-doubt").status());
        assertEquals(1, store.paymentCount("op-doubt"), "no debe reinsertar");
        assertEquals(0, jdbcDelta(), "in-doubt no re-ejecuta JDBC");
    }

    // Caso 5 Rama 3 — conciliación de IN_DOUBT con fila de PAYLOAD_HASH distinto = BD
    // inconsistente → DLQ. El mismatch en IN_DOUBT (no en PROCESSING) es el que termina en
    // cuarentena por `reconcileInDoubt`.
    @Test
    void caso5_rama3_paymentHashMismatchEsDlq() throws Exception {
        store.seedInDoubt("op-bad", HASH_OP001, "f".repeat(64), "SYNTHETIC_PAYMENTS", List.of());
        ProcessingOutcome outcome = handle(validEvent("op-bad"));
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.DLQ_QUARANTINED, store.get("op-bad").status());
    }

    // Caso 5 Rama 2 — operación en RETRYABLE sin commit: el redelivery la reprocesa completa → SUCCEEDED.
    @Test
    void caso5_rama2_retryableSinCommitReintentaFlujoCompleto() throws Exception {
        PaymentCommittedEvent retry = validEvent("op-retry");
        store.reserveAtomic(new OperationState("op-retry", "evt-001", "wtr-r", "msg-r",
                HASH_OP001, OperationStatus.PROCESSING, 1, null, null, null));
        store.update(store.get("op-retry").withStatus(OperationStatus.RETRYABLE));
        ProcessingOutcome outcome = handle(retry);
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(1, store.paymentCount("op-retry"));
    }

    // Caso 6 — data del envelope no es Base64 válido → DLQ en el decode.
    @Test
    void caso6_base64CorruptoEsDlq() throws Exception {
        ProcessingOutcome outcome = workerService.handleRaw(envelopeRaw(mapper, "!!not-valid-base64!!", "msg-x"));
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack());
    }

    // Caso 6 — el Base64 decodifica pero no es JSON (es solo "{") → DLQ al parsear.
    @Test
    void caso6_jsonMalformadoEsDlq() throws Exception {
        String raw = envelopeRaw(mapper, Base64.getEncoder().encodeToString("{".getBytes()), "msg-x");
        assertEquals(OperationStatus.DLQ_QUARANTINED, workerService.handleRaw(raw).status());
    }

    // Caso 6 — JSON válido pero le faltan operationId/operationData → DLQ estructural.
    @Test
    void caso6_faltanOperationIdYPayloadEsDlq() throws Exception {
        PaymentCommittedEvent missing = new PaymentCommittedEvent("evt-006", "PAYMENT_COMMITTED",
                "prizes.payment", "agg-006", 1, "SYBASE", "2026-09-07T00:00:00Z",
                "corr-006", null, null);
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(missing).status());
    }

    // Plan F5 prueba 1 — eventVersion inválida → DLQ estructural (schema del contrato).
    @Test
    void eventVersionInvalidaEsDlqEstructural() throws Exception {
        PaymentCommittedEvent e = new PaymentCommittedEvent("evt-1", "PAYMENT_COMMITTED", "prizes.payment",
                "agg-1", 2, "SYBASE", "2026-09-07T00:00:00Z", "corr-1", "op-ver", validData());
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(e).status());
    }

    // Plan F5 prueba 4 — destinationSystem != SYBASE → DLQ estructural (ruteo equivocado).
    @Test
    void destinationSystemDistintoDeSybaseEsDlq() throws Exception {
        PaymentCommittedEvent e = new PaymentCommittedEvent("evt-4", "PAYMENT_COMMITTED", "prizes.payment",
                "agg-4", 1, "ORACLE", "2026-09-07T00:00:00Z", "corr-4", "op-dst", validData());
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(e).status());
    }

    // Plan F5 prueba 5 — campo obligatorio del operationData ausente → DLQ estructural.
    @Test
    void campoObligatorioAusenteEnOperationDataEsDlq() throws Exception {
        OperationData badData = new OperationData(null, "claim-001", "COMMITTED", "CASH",
                new BigDecimal("200.00"), new BigDecimal("50.00"), new BigDecimal("150.00"), "USD");
        PaymentCommittedEvent e = new PaymentCommittedEvent("evt-5", "PAYMENT_COMMITTED", "prizes.payment",
                "agg-5", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-5", "op-miss", badData);
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(e).status());
    }

    // Plan F5 prueba 6 — montos negativos → DLQ estructural.
    @Test
    void montosNegativosEsDlq() throws Exception {
        OperationData negData = new OperationData("pay-6", "claim-6", "COMMITTED", "CASH",
                new BigDecimal("-1.00"), BigDecimal.ZERO, new BigDecimal("-1.00"), "USD");
        PaymentCommittedEvent e = new PaymentCommittedEvent("evt-6", "PAYMENT_COMMITTED", "prizes.payment",
                "agg-6", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-6", "op-neg", negData);
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(e).status());
    }

    // Plan F5 prueba 7 (E2E) — invariante monetaria incumplida → REJECTED (validationRules).
    @Test
    void invarianteMonetariaVioladaEsRejected() throws Exception {
        OperationData badData = new OperationData("pay-7", "claim-7", "COMMITTED", "CASH",
                new BigDecimal("200.00"), new BigDecimal("50.00"), new BigDecimal("100.00"), "USD");
        PaymentCommittedEvent e = new PaymentCommittedEvent("evt-7", "PAYMENT_COMMITTED", "prizes.payment",
                "agg-7", 1, "SYBASE", "2026-09-07T00:00:00Z", "corr-7", "op-inv", badData);
        ProcessingOutcome outcome = handle(e);
        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertEquals(OperationStatus.REJECTED, store.get("op-inv").status());
        assertEquals(0, store.paymentCount("op-inv"));
    }

    // Plan F5 prueba 10 — duplicado en JDBC_COMMITTED: SOLO reintenta el reporte (nunca el JDBC).
    @Test
    void duplicadoEnJdbcCommittedSoloReintentaReporte() throws Exception {
        store.seedJdbcCommitted("op-jdbc", HASH_OP001, "SYNTHETIC_PAYMENTS", List.of());
        ProcessingOutcome outcome = handle(validEvent("op-jdbc"));
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(0, jdbcDelta(), "no debe re-ejecutarse JDBC en el retry del reporte");
        assertEquals(1, store.paymentCount("op-jdbc"));
    }

    // Plan F5 prueba 12 — JDBC UNKNOWN → IN_DOUBT y la redelivery concilia a SUCCEEDED sin reejecutar JDBC.
    @Test
    void jdbcUnknowngeneraInDoubtYSeConcilia() throws Exception {
        jdbc.setForcedOutcome(JdbcOutcome.UNKNOWN);
        ProcessingOutcome first = handle(validEvent("op-unk"));
        assertEquals(OperationStatus.RETRYABLE, first.status());
        assertFalse(first.ack(), "UNKNOWN -> NACK; la redelivery concilia");
        assertEquals(OperationStatus.IN_DOUBT, store.get("op-unk").status());
        assertEquals(1, store.paymentCount("op-unk"), "la fila se persistió en el commit in-doubt");

        jdbc.resetOutcome();
        ProcessingOutcome second = handle(validEvent("op-unk"));
        assertEquals(OperationStatus.SUCCEEDED, second.status());
        assertTrue(second.ack());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-unk").status());
        assertEquals(1, jdbcDelta(), "la conciliación no re-ejecuta JDBC");
        assertEquals(1, store.paymentCount("op-unk"));
    }

    // Plan F5 prueba 13 — fallo temporal del reporte → RETRYABLE, y la redelivery lo lleva a
    // SUCCEEDED SOLO reintentando el reporte (sin segunda ejecución JDBC).
    @Test
    void falloTemporalDelReporteRecuperaSinReejecutarJdbc() throws Exception {
        reporter.setOverride(result -> ReportResult.failed("timeout simulado"));
        ProcessingOutcome first = handle(validEvent("op-rep"));
        assertEquals(OperationStatus.RETRYABLE, first.status());
        assertFalse(first.ack());
        assertEquals(OperationStatus.RETRYABLE, store.get("op-rep").status());
        assertEquals(1, store.paymentCount("op-rep"), "el JDBC SÍ confirmó");

        reporter.resetOverride();
        ProcessingOutcome second = handle(validEvent("op-rep"));
        assertEquals(OperationStatus.SUCCEEDED, second.status());
        assertEquals(1, jdbcDelta(), "el retry del reporte no re-ejecuta JDBC");
        assertEquals(1, store.paymentCount("op-rep"));
    }

    // Plan F5 prueba 14 — 409 del endpoint (CONFLICT) → promueve a SUCCEEDED sin re-ejecutar JDBC.
    @Test
    void reporteConflict409NoReejecutaJdbc() throws Exception {
        reporter.setOverride(result -> ReportResult.conflict("ya reportado"));
        ProcessingOutcome outcome = handle(validEvent("op-conf"));
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-conf").status());
        assertEquals(1, jdbcDelta(), "el 409 no provoca segunda ejecución JDBC");
        assertEquals(1, store.paymentCount("op-conf"));
    }

    // Plan F5 prueba 15 — redelivery completa una operación que ya consta como reportada sin
    // segunda ejecución JDBC (mismo hash, estado SUCCEEDED -> ACK idempotente).
    @Test
    void redeliveryTrasSucceededNoReejecutaJdbc() throws Exception {
        handle(validEvent("op-suc"));
        ProcessingOutcome outcome = handle(validEvent("op-suc"));
        assertEquals(OperationStatus.IDEMPOTENT, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(1, jdbcDelta(), "nunca hay segunda ejecución JDBC");
    }

    // El Traductor real no inventa columnas: su plan tiene exactamente 12 parámetros (8 + 4).
    @Test
    void traductorRealEmiteDoceParametros() throws Exception {
        PaymentCommittedEvent e = validEvent("op-12");
        ProcessingOutcome outcome = handle(e);
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(12, store.findPayment("op-12").parameters().size(),
                "8 de negocio + OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH");
    }
}