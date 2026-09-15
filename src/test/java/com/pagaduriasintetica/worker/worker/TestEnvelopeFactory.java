package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.SyntheticPayload;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.Base64;

/**
 * Factoria de envelopes/eventos compartida por las suites E2E y el harness de evidencia.
 * Fuente única de la forma exacta del envelope Pub/Sub y del evento de negocio del contrato,
 * para que todas las pruebas envíen EXACTAMENTE el mismo fixture (y el mismo hash canónico).
 */
public final class TestEnvelopeFactory {

    // Suscripción Pub/Sub real del proyecto GCP hacia donde iría el Worker en producción.
    public static final String SUBSCRIPTION = "projects/dulcet-listener-505916-n5/subscriptions/synthetic-payment-sub";
    // Hash canónico del evento de negocio sintético (contrato §2.1). Verificado por PayloadHasherTest.
    public static final String HASH_OP001 = "8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6";

    private TestEnvelopeFactory() {
    }

    /**
     * Evento de negocio canónico del contrato (payload "claim-001 / 150.00 / Ana Gomez").
     * El operationId se pasa por parámetro para reutilizarlo en todos los escenarios.
     */
    public static SyntheticEvent validEvent(String operationId) {
        return new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-001", "SYNTHETIC_PAYMENT_REQUESTED",
                operationId, "trace-001", "2026-09-07T00:00:00Z", "INSERT", "synthetic_payment",
                new SyntheticPayload("claim-001", new BigDecimal("150.00"), "2026-09-07", "Ana Gomez"));
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
        return mapper.writeValueAsString(root);
    }

    /**
     * Atajo: serializa el evento, lo Base64-encodea y lo mete dentro del envelope Pub/Sub.
     * Es la forma de "publicar" un evento válido en las pruebas E2E.
     */
    public static String pushBody(ObjectMapper mapper, SyntheticEvent event, String messageId) throws Exception {
        String data = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(event));
        return envelopeRaw(mapper, data, messageId);
    }
}