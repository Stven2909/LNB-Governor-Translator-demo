package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.SyntheticPayload;
import com.pagaduriasintetica.worker.governor.MockGovernor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.HASH_OP001;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.envelopeRaw;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.pushBody;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Suite E2E de la prueba vertical (contexto Spring, beans reales): corre los 6 casos de LNB
// + subcasos (2a-2c, 3b, ramas 1/2/3 de in-doubt) contra el pipeline completo.
@SpringBootTest
class WorkerPipelineTest {

    @Autowired
    ObjectMapper mapper;
    @Autowired
    InMemoryStateStore store;
    @Autowired
    MockGovernor governor;
    @Autowired
    WorkerService workerService;

    @BeforeEach
    void reset() {
        store.reset();
        governor.resetOverride();
    }

    // Envía el evento por el pipeline completo (serializa + Base64 + envelope) y devuelve
    // el outcome del Worker, igual que un POST /push con messageId logístico fijo.
    ProcessingOutcome handle(SyntheticEvent event) throws Exception {
        return workerService.handleRaw(pushBody(mapper, event, "msg-x"));
    }

    @Test
    // Caso 1 — pipeline completo: reserva → Gobernador → Traductor → commit → ACK SUCCEEDED.
    void caso1_nuevoValidoLlegaASucceeded() throws Exception {
        ProcessingOutcome outcome = handle(validEvent("op-001"));
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-001").status());
        assertNotNull(store.findPayment("op-001"));
        assertEquals(HASH_OP001, store.findPayment("op-001").payloadHash());
    }

    @Test
    // Caso 2a — reenvío idéntico (mismo hash canónico) → IDEMPOTENT, sin segundo insert.
    void caso2a_duplicadoMismoHashEsIdempotent() throws Exception {
        handle(validEvent("op-001"));
        ProcessingOutcome outcome = handle(validEvent("op-001"));
        assertEquals(OperationStatus.IDEMPOTENT, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(1, store.paymentCount("op-001"), "no debe duplicarse la inserción");
    }

    @Test
    // Caso 2b — evento ya "en vuelo" (PROCESSING) en la BD → NACK RETRYABLE (redelivery).
    void caso2b_eventoEnVueloRespuestaNack() throws Exception {
        SyntheticEvent flight = validEvent("op-flight");
        String hash = new PayloadHasher().hash(flight.payload());
        store.reserve(new OperationState("op-flight", "evt-001", "trace-001", "msg-flight",
                hash, OperationStatus.PROCESSING, 1, null, null, null));
        ProcessingOutcome outcome = handle(flight);
        assertFalse(outcome.ack(), "respuesta NACK para redelivery programada");
        assertEquals(OperationStatus.RETRYABLE, outcome.status());
        assertEquals(0, store.paymentCount("op-flight"));
    }

    @Test
    // Caso 2c — mismo operationId pero distinto payload = PK collision → DLQ (no redelivery).
    void caso2c_operationIdRecicladoConHashDiferenteEsDlq() throws Exception {
        handle(validEvent("op-001"));
        SyntheticEvent recycled = validEvent("op-001");
        SyntheticEvent differentPayload = new SyntheticEvent(
                recycled.contract_version(), recycled.eventId(), recycled.eventType(), recycled.operationId(),
                recycled.traceId(), recycled.occurredAt(), recycled.operation(), recycled.entity(),
                new SyntheticPayload("claim-001", new BigDecimal("199.00"), "2026-09-07", "Ana Gomez"));
        ProcessingOutcome outcome = handle(differentPayload);
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.DLQ_QUARANTINED, store.get("op-001").status());
        assertEquals(1, store.paymentCount("op-001"));
    }

    @Test
    // Caso 3 — entidad fuera de la whitelist del catálogo → REJECTED sin invocar a Vertex.
    void caso3_entidadFueraDelCatalogoEsRejected() throws Exception {
        SyntheticEvent unauthorized = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-003",
                "SYNTHETIC_PAYMENT_REQUESTED", "op-rej", "trace-003", "2026-09-07T00:00:00Z",
                "INSERT", "payroll_secret", new SyntheticPayload("c1", new BigDecimal("100"), "2026-09-07", "B"));
        ProcessingOutcome outcome = handle(unauthorized);
        assertEquals(OperationStatus.REJECTED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.REJECTED, store.get("op-rej").status());
        assertEquals(0, store.paymentCount("op-rej"));
    }

    @Test
    // Caso 3b — entidad permitida pero con operación (DELETE) prohibida → REJECTED.
    void caso3b_operacionFueraDelCatalogoEsRejected() throws Exception {
        SyntheticEvent op = validEvent("op-rej-2");
        SyntheticEvent del = new SyntheticEvent(op.contract_version(), op.eventId(), op.eventType(), op.operationId(),
                op.traceId(), op.occurredAt(), "DELETE", op.entity(), op.payload());
        assertEquals(OperationStatus.REJECTED, handle(del).status());
    }

    @Test
    // Caso 4 — el Gobernador "alucina" una tabla que no está en el catálogo → DLQ.
    void caso4_alucinacionDelGobernadorEsDlq() throws Exception {
        governor.setOverride(event -> new GovernorContract(event.contract_version(), GovernorDecision.APPROVED,
                event.operationId(), event.traceId(), "hallucinated", "TABLA_INVENTADA",
                null, null, null, "CATALOG_SYNTHETIC_V0", List.of()));
        ProcessingOutcome outcome = handle(validEvent("op-hall"));
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.reason().contains("Governor output violated catalog whitelist"));
        assertEquals(0, store.paymentCount("op-hall"));
    }

    @Test
    // Caso 5 Rama 1 — in-doubt: el pago ya se commitó; se promueve a SUCCEEDED sin reinsertar.
    void caso5_rama1_inDoubtPromueveASucceededSinReinsertar() throws Exception {
        store.seedDoubtful("op-doubt", HASH_OP001, HASH_OP001, "SYNTHETIC_PAYMENTS", List.of());
        ProcessingOutcome outcome = handle(validEvent("op-doubt"));
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertTrue(outcome.ack());
        assertEquals(OperationStatus.SUCCEEDED, store.get("op-doubt").status());
        assertEquals(1, store.paymentCount("op-doubt"), "no debe reinsertar");
    }

    @Test
    // Caso 5 Rama 3 — fila de pago existente con PAYLOAD_HASH distinto = BD inconsistente → DLQ.
    void caso5_rama3_paymentHashMismatchEsDlq() throws Exception {
        store.seedDoubtful("op-bad", HASH_OP001, "f".repeat(64), "SYNTHETIC_PAYMENTS", List.of());
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(validEvent("op-bad")).status());
    }

    @Test
    // Caso 5 Rama 2 — operación en RETRYABLE: el redelivery la reprocesa completa → SUCCEEDED.
    void caso5_rama2_retryableSinCommitReintentaFlujoCompleto() throws Exception {
        SyntheticEvent retry = validEvent("op-retry");
        String hash = new PayloadHasher().hash(retry.payload());
        store.reserve(new OperationState("op-retry", "evt-001", "trace-001", "msg-r",
                hash, OperationStatus.PROCESSING, 1, null, null, null));
        store.update(store.get("op-retry").withStatus(OperationStatus.RETRYABLE));
        ProcessingOutcome outcome = handle(retry);
        assertEquals(OperationStatus.SUCCEEDED, outcome.status());
        assertEquals(1, store.paymentCount("op-retry"));
    }

    @Test
    // Caso 6 — data del envelope no es Base64 válido → DLQ en el decode.
    void caso6_base64CorruptoEsDlq() throws Exception {
        ProcessingOutcome outcome = workerService.handleRaw(envelopeRaw(mapper, "!!not-valid-base64!!", "msg-x"));
        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack());
    }

    @Test
    // Caso 6 — el Base64 decodifica pero no es JSON (es solo "{") → DLQ al parsear.
    void caso6_jsonMalformadoEsDlq() throws Exception {
        String raw = envelopeRaw(mapper, Base64.getEncoder().encodeToString("{".getBytes()), "msg-x");
        assertEquals(OperationStatus.DLQ_QUARANTINED, workerService.handleRaw(raw).status());
    }

    @Test
    // Caso 6 — JSON válido pero le faltan operationId/traceId/payload → DLQ estructural.
    void caso6_faltanOperationIdYPayloadEsDlq() throws Exception {
        SyntheticEvent missing = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-006",
                "SYNTHETIC_PAYMENT_REQUESTED", null, null, null, "INSERT", "synthetic_payment", null);
        assertEquals(OperationStatus.DLQ_QUARANTINED, handle(missing).status());
    }
}