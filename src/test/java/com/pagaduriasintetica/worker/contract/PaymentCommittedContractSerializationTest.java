package com.pagaduriasintetica.worker.contract;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.governor.Governor;
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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Evidencia Steven (JD14 + andamiaje V1): el contrato tipado PAYMENT_COMMITTED
 * serializa/deserializa sin perdida. Montos con compareTo (BigDecimal.equals es sensible a
 * escala), resto por igualdad. V1 exige rechazar campos desconocidos: el pipeline usa lector
 * estricto (WorkerService); instrumentIds es opcional hasta el corte V1 del productor.
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
    void propiedadNoDeclaradaSeRechazaConLectorEstricto() throws Exception {
        // V1 (decisión JD 14/10): root y operationData rechazan desconocidos. El pipeline usa
        // este mismo lector estricto (WorkerService); el JsonMapper plano sigue tolerando por
        // defecto, y por eso la configuración explícita importa.
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-3");
        String json = mapper.writeValueAsString(base);
        String conExtra = json.replace("\"operationData\":{",
                "\"operationData\":{\"campoFuturaV2\":\"x\",");

        Exception e = assertThrows(Exception.class, () -> mapper
                .readerFor(PaymentCommittedEvent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .<PaymentCommittedEvent>readValue(conExtra));
        assertTrue(e.getMessage() != null && e.getMessage().contains("campoFuturaV2"),
                "el rechazo debe mencionar la propiedad desconocida: " + e.getMessage());
    }

    @Test
    void roundTripConInstrumentIdsPreservaOrdenYHashCongelado() throws Exception {
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-5");
        OperationData conItems = new OperationData(
                base.operationData().paymentId(), base.operationData().claimId(),
                base.operationData().status(), base.operationData().paymentMethod(),
                base.operationData().grossAmount(), base.operationData().withholdingAmount(),
                base.operationData().netAmount(), base.operationData().currency(),
                List.of("inst-2", "inst-1"));
        PaymentCommittedEvent event = new PaymentCommittedEvent(
                base.eventId(), base.eventType(), base.aggregateType(), base.aggregateId(),
                base.eventVersion(), base.destinationSystem(), base.occurredAt(),
                base.correlationId(), base.operationId(), conItems);

        String json = mapper.writeValueAsString(event);
        PaymentCommittedEvent decoded = mapper.readValue(json, PaymentCommittedEvent.class);

        assertEquals(List.of("inst-2", "inst-1"), decoded.operationData().instrumentIds(),
                "el orden emitido se preserva tal cual (el productor garantiza determinismo)");
        assertTrue(validator.validate(event).isEmpty());
        assertEquals(new PayloadHasher().hash(base.operationData()),
                new PayloadHasher().hash(decoded.operationData()),
                "fórmula congelada: el hash aún no incluye instrumentIds (pendiente V1 Alex)");
    }

    @Test
    void reglasInstrumentIdsDuplicadoBlancoYNulo() {
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-6");

        assertTrue(validator.validate(conInstrumentos(base, "op-serial-6a", List.of("a", "a")))
                        .contains("operationData.instrumentIds duplicate"),
                "duplicados deben rechazarse");
        assertTrue(validator.validate(conInstrumentos(base, "op-serial-6b", List.of("ok", " ")))
                        .contains("operationData.instrumentIds invalid"),
                "blancos deben rechazarse");
        // null = puerta V1: los fixtures actuales no traen el campo y siguen válidos.
        assertTrue(validator.validate(base).isEmpty());
    }

    @Test
    void campoDesconocidoEnPipelineVaADlqSinJdbc() throws Exception {
        WorkerService service = pipelineSinDependenciasExternas();
        PaymentCommittedEvent base = TestEnvelopeFactory.validEvent("op-serial-7");
        String json = mapper.writeValueAsString(base);
        String conExtra = json.replace("\"operationData\":{",
                "\"operationData\":{\"campoFuturaV2\":\"x\",");
        String data = Base64.getEncoder().encodeToString(
                conExtra.getBytes(StandardCharsets.UTF_8));
        String raw = TestEnvelopeFactory.envelopeRaw(mapper, data, "msg-serial-7");

        ProcessingOutcome outcome = service.handleRaw(raw);

        assertEquals(OperationStatus.DLQ_QUARANTINED, outcome.status());
        assertTrue(outcome.ack(), "un mensaje con campo desconocido no se reintenta");
    }

    private PaymentCommittedEvent conInstrumentos(PaymentCommittedEvent base, String operationId,
                                                  List<String> ids) {
        OperationData data = new OperationData(
                base.operationData().paymentId(), base.operationData().claimId(),
                base.operationData().status(), base.operationData().paymentMethod(),
                base.operationData().grossAmount(), base.operationData().withholdingAmount(),
                base.operationData().netAmount(), base.operationData().currency(), ids);
        return new PaymentCommittedEvent(
                base.eventId(), base.eventType(), base.aggregateType(), base.aggregateId(),
                base.eventVersion(), base.destinationSystem(), base.occurredAt(),
                base.correlationId(), operationId, data);
    }

    /**
     * Pipeline real con Governor/Traductor mockeados (nunca se invocan en esta ruta: el
     * rechazo ocurre en el parseo). Misma construcción que GovernorFailureClassificationTest.
     */
    private WorkerService pipelineSinDependenciasExternas() throws Exception {
        Catalog catalog = new Catalog(mapper);
        InMemoryStateStore store = new InMemoryStateStore(new OperationStateMachine());
        FixtureJdbcExecutor jdbc = new FixtureJdbcExecutor(store);
        Governor governor = mock(Governor.class);
        Translator translator = mock(Translator.class);
        return new WorkerService(mapper, new PayloadHasher(), new StructuralValidator(),
                catalog, governor, translator, store, jdbc, new MockResultReporter(), 3, 5);
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
                "withholdingAmount", "netAmount", "currency", "instrumentIds"}) {
            assertTrue(json.contains("\"" + clave + "\""),
                    "el JSON debe llevar la clave camelCase " + clave);
        }
    }
}
