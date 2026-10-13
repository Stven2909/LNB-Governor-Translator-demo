package com.pagaduriasintetica.worker.contract;

import com.pagaduriasintetica.worker.worker.PayloadHasher;
import com.pagaduriasintetica.worker.worker.StructuralValidator;
import com.pagaduriasintetica.worker.worker.TestEnvelopeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidencia Steven (JD14): el contrato tipado PAYMENT_COMMITTED serializa/deserializa sin
 * perdida. Montos con compareTo (BigDecimal.equals es sensible a escala), resto por igualdad.
 * La sonda hacia adelante documenta el comportamiento actual ante una propiedad no declarada
 * (p. ej. instrumentIds): el productor debe congelar el shape antes de enviarlo.
 */
class PaymentCommittedContractSerializationTest {

    private ObjectMapper mapper;
    private StructuralValidator validator;

    @BeforeEach
    void setUp() {
        mapper = JsonMapper.builder().build();
        validator = new StructuralValidator();
    }

    @Test
    void roundTripConservaTiposExactosYPrecisionMonetaria() throws Exception {
        PaymentCommittedEvent original = TestEnvelopeFactory.validEvent("op-serial-1");

        String json = mapper.writeValueAsString(original);

        assertTrue(json.contains("\"operationData\""), "camelCase en el JSON: " + json);
        assertTrue(json.contains("\"eventVersion\":1"), "eventVersion presente: " + json);
        assertTrue(json.contains("\"grossAmount\":"), "montos en camelCase: " + json);

        PaymentCommittedEvent decoded = mapper.readValue(json, PaymentCommittedEvent.class);

        assertEquals(original.eventId(), decoded.eventId());
        assertEquals(original.eventType(), decoded.eventType());
        assertEquals(original.aggregateType(), decoded.aggregateType());
        assertEquals(original.aggregateId(), decoded.aggregateId());
        assertEquals(original.eventVersion(), decoded.eventVersion());
        assertEquals(original.destinationSystem(), decoded.destinationSystem());
        assertEquals(original.occurredAt(), decoded.occurredAt());
        assertEquals(original.correlationId(), decoded.correlationId());
        assertEquals(original.operationId(), decoded.operationId());
        assertEquals(original.operationData().paymentId(), decoded.operationData().paymentId());
        assertEquals(original.operationData().claimId(), decoded.operationData().claimId());
        assertEquals(original.operationData().status(), decoded.operationData().status());
        assertEquals(original.operationData().paymentMethod(), decoded.operationData().paymentMethod());
        assertEquals(original.operationData().currency(), decoded.operationData().currency());
        // BigDecimal.equals compara escala ("200.00" != "200.0"): la precision monetaria se
        // verifica con compareTo, que es lo que usa la invariante de negocio.
        assertEquals(0, original.operationData().grossAmount()
                .compareTo(decoded.operationData().grossAmount()));
        assertEquals(0, original.operationData().withholdingAmount()
                .compareTo(decoded.operationData().withholdingAmount()));
        assertEquals(0, original.operationData().netAmount()
                .compareTo(decoded.operationData().netAmount()));

        assertTrue(validator.validate(decoded).isEmpty(),
                "el evento valido pasa StructuralValidator: " + validator.validate(decoded));
        assertEquals(new PayloadHasher().hash(original.operationData()),
                new PayloadHasher().hash(decoded.operationData()),
                "el hash canonico es estable ante el round-trip");
    }

    @Test
    void eventVersionErroneaYOperationIdAusenteSonEstructuralmenteInvalidos() {
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-2");

        PaymentCommittedEvent versionMala = new PaymentCommittedEvent(
                base.eventId(), base.eventType(), base.aggregateType(), base.aggregateId(), 2,
                base.destinationSystem(), base.occurredAt(), base.correlationId(),
                base.operationId(), base.operationData());
        List<String> errorsVersion = validator.validate(versionMala);
        assertTrue(errorsVersion.contains("eventVersion must be 1"),
                "eventVersion=2 debe rechazarse: " + errorsVersion);

        PaymentCommittedEvent sinOperationId = new PaymentCommittedEvent(
                base.eventId(), base.eventType(), base.aggregateType(), base.aggregateId(),
                base.eventVersion(), base.destinationSystem(), base.occurredAt(),
                base.correlationId(), " ", base.operationData());
        List<String> errorsId = validator.validate(sinOperationId);
        assertTrue(errorsId.contains("operationId required"),
                "operationId ausente debe rechazarse: " + errorsId);
    }

    @Test
    void sondaPropiedadNoDeclaradaDocumentaComportamientoActual() throws Exception {
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-3");
        String json = mapper.writeValueAsString(base);
        String conInstrumentIds = json.replace("\"operationData\":{",
                "\"operationData\":{\"instrumentIds\":[\"inst-1\",\"inst-2\"],");

        try {
            PaymentCommittedEvent decoded =
                    mapper.readValue(conInstrumentIds, PaymentCommittedEvent.class);
            // Si el mapper tolera la propiedad, el contrato tipado la ignora: no forma parte
            // del hash ni de la validacion hasta que Alex congele el shape.
            assertEquals(TestEnvelopeFactory.validEvent("op-serial-3").operationData().paymentId(),
                    decoded.operationData().paymentId());
            assertEquals(new PayloadHasher().hash(base.operationData()),
                    new PayloadHasher().hash(decoded.operationData()),
                    "una propiedad no declarada no debe alterar el hash canonico");
        } catch (Exception e) {
            // Comportamiento estricto (FAIL_ON_UNKNOWN_PROPERTIES): el consumidor rechaza el
            // payload. Es la evidencia tecnica de que el shape de instrumentIds debe nacer del
            // productor (Alex) antes de enviarse; documentar, no "arreglar" tolerandolo.
            assertTrue(e.getMessage() != null && e.getMessage().contains("instrumentIds"),
                    "el rechazo debe mencionar la propiedad desconocida: " + e.getMessage());
        }
    }

    @Test
    void clavesDelContratoRespetanVocabularioOficial() throws Exception {
        // Guarda de contrato: si el productor cambia una clave o el tipado, esta prueba y el
        // JSON serializado lo evidencian. Fuente: lnb-docs/CONTRACT_EVENTO_V0.1.md (Alex 17/09).
        PaymentCommittedEvent event = TestEnvelopeFactory.validEvent("op-serial-4");
        assertEquals("PAYMENT_COMMITTED", event.eventType());
        assertEquals("prizes.payment", event.aggregateType());
        assertEquals(1, event.eventVersion());
        assertEquals("SYBASE", event.destinationSystem());
        assertTrue(validator.validate(event).isEmpty());

        String json = mapper.writeValueAsString(event);
        for (String clave : new String[]{"eventId", "eventType", "aggregateType", "aggregateId",
                "eventVersion", "destinationSystem", "occurredAt", "correlationId", "operationId",
                "operationData", "paymentId", "claimId", "status", "paymentMethod", "grossAmount",
                "withholdingAmount", "netAmount", "currency"}) {
            assertTrue(json.contains("\"" + clave + "\""),
                    "el JSON debe llevar la clave camelCase " + clave);
        }
    }
}
