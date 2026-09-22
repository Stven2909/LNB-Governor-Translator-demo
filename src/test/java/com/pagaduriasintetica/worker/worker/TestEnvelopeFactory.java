package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Base64;

/**
 * Factoria de envelopes/eventos compartida por las suites E2E y el harness de evidencia.
 * Fuente única de la forma exacta del envelope Pub/Sub y del evento PAYMENT_COMMITTED del
 * contrato de Alex, para que todas las pruebas envíen EXACTAMENTE el mismo fixture (y el mismo
 * hash canónico). Fixture: pay-001 / claim-001 / COMMITTED / CASH / 200.00 - 50.00 = 150.00 / USD.
 */
public final class TestEnvelopeFactory {

    // Suscripción Pub/Sub real del proyecto GCP hacia donde iría el Worker en producción.
    public static final String SUBSCRIPTION = "projects/dulcet-listener-505916-n5/subscriptions/synthetic-payment-sub";
    // Hash canónico del operationData del fixture (contrato PAYMENT_COMMITTED V0.1).
    // Canon: {"claimId":"claim-001","currency":"USD","grossAmount":"200.00","netAmount":"150.00",
    //         "paymentId":"pay-001","paymentMethod":"CASH","status":"COMMITTED","withholdingAmount":"50.00"}.
    public static final String HASH_OP001 = "0fd5240accd7b41a9d55d95567eb79b0f87a9776c0396a5f888d9c7a99c246a8";

    private TestEnvelopeFactory() {
    }

    /** operationData canónico del contrato (pay-001 / claim-001 / COMMITTED / CASH / 200.00 etc.). */
    public static OperationData validData() {
        return new OperationData("pay-001", "claim-001", "COMMITTED", "CASH",
                new BigDecimal("200.00"), new BigDecimal("50.00"), new BigDecimal("150.00"), "USD");
    }

    /** Evento de negocio canónico del contrato; el operationId se pasa por parámetro. */
    public static PaymentCommittedEvent validEvent(String operationId) {
        return new PaymentCommittedEvent("evt-001", "PAYMENT_COMMITTED", "prizes.payment", "agg-001",
                1, "SYBASE", "2026-09-07T00:00:00Z", "corr-001", operationId, validData());
    }

    /**
     * Envelope Pub/Sub concreto: message.data = Base64 ya calculado por el llamador.
     * messageId es la "llave de transporte" del push; la deduplicación de negocio la hace
     * el pipeline con operationId + PAYLOAD_HASH (el messageId es solo logístico).
     */
    public static String envelopeRaw(ObjectMapper mapper, String base64Data, String messageId) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("subscription", SUBSCRIPTION);
        ObjectNode message = root.putObject("message");
        message.put("data", base64Data);
        message.put("messageId", messageId);
        message.put("publishTime", "2026-09-07T00:00:01Z");
        root.put("deliveryAttempt", 1);
        return mapper.writeValueAsString(root);
    }

    /** Atajo: serializa el evento, lo Base64-encodea y lo mete dentro del envelope Pub/Sub. */
    public static String pushBody(ObjectMapper mapper, PaymentCommittedEvent event, String messageId) throws Exception {
        String data = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(event));
        return envelopeRaw(mapper, data, messageId);
    }
}