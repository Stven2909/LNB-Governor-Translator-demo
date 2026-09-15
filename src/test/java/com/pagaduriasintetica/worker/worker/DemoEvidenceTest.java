package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.OperationState;
import com.pagaduriasintetica.worker.contract.OperationStatus;
import com.pagaduriasintetica.worker.contract.PaymentRow;
import com.pagaduriasintetica.worker.contract.ProcessingOutcome;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.SyntheticPayload;
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
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validEvent;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Harness E2E de demo: corre la matriz de 16 escenarios de la prueba vertical LNB contra el
 * pipeline real (contexto Spring) y exporta la evidencia a target/demo/ vía EvidenceWriter.
 *
 * Cada escenario es un método casoXX_* que (1) construye el envelope exacto, (2) lo envía por
 * POST/PUBSIM `handleRaw` y (3) registra en un "expediente" (Map) todo lo que pasó: evento
 * decodificado, hash, actores, estado final, pago, cuarentena. El expediente se exporta al final.
 *
 * Regla importante: {@code escenario(...)} resetea StateStore y Gobernador al inicio de CADA
 * escenario, para que el override de un caso (p.ej. Caso 4) nunca se filtre al siguiente.
 */
@SpringBootTest
class DemoEvidenceTest {

    // Expedientes de los 16 escenarios, compartidos con @AfterAll que los exporta a target/demo/.
    private static final List<Map<String, Object>> EVIDENCIAS = new ArrayList<>(16);

    // Beans reales del contexto Spring: se usan los mocks del Sybase, el Gobernador y el
    // Traductor que componen el pipeline "real" de la PoC (nada aislado salvo el escenario 15).
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
    WorkerService workerService;

    @BeforeEach
    void reset() {
        store.reset();             // StateStore limpio como una BD de cero
        governor.resetOverride();  // Gobernador vuelve a su default (APPROVED con plan de catálogo)
    }

    @AfterAll
    static void exportarEvidencia() throws Exception {
        EvidenceWriter.escribir(EVIDENCIAS);
    }

    @Test
    void matrizCompletaDeEvidencia() {
        // Orden = narrativa de la demo: casos felices primero, luego idempotencia/conflictos,
        // malformados, rechazos y por último el hash canónico puro.
        caso1_nuevoValido();             // Caso 1: pipeline completo → SUCCEEDED + pago persistido
        caso2a_duplicadoIdempotente();   // Caso 2a: reenvío idéntico → IDEMPOTENT, sin duplicar
        caso2b_inFlightRetryable();      // Caso 2b: evento "en vuelo" (PROCESSING) → NACK RETRYABLE
        caso2c_pkCollisionDlq();         // Caso 2c: mismo ID con otra carga → DLQ (PK collision)
        caso3_prefiltroRejected();       // Caso 3: entidad no autorizada → REJECTED sin Vertex
        caso3b_operacionNoPermitida();   // Caso 3b: operación DELETE → REJECTED por el catálogo
        caso4_governorAlucinaDlq();      // Caso 4: Gobernador inventa tabla → DLQ del contrato
        caso5r1_inDoubtRecuperado();     // Caso 5 Rama 1: in-doubt con hash igual → SUCCEEDED
        caso5r2_retryReprocesado();      // Caso 5 Rama 2: RETRYABLE → reproceso → SUCCEEDED
        caso5r3_inDoubtHashDistintoDlq();// Caso 5 Rama 3: hash distinto en BD → DLQ inconsistencia
        caso6_base64CorruptoDlq();       // Caso 6: Base64 inválido → DLQ
        caso6_jsonMalformadoDlq();       // Caso 6: JSON del evento roto → DLQ
        caso6_faltanCamposDlq();         // Caso 6: faltan operationId y payload → DLQ estructural
        paso9_governorRechazado();       // Paso 9: el Gobernador rechaza por regla de negocio → REJECTED
        translation_errorColumnaInventada(); // no_invented_columns: Traductor inventa columna → TRANSLATION_ERROR
        hasher_contrato();               // Contrato §2.1: verificación pura del PAYLOAD_HASH canónico

        // El harness NO falla en el primer escenario con FAIL: registra todo y luego afirma.
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
            SyntheticEvent e = validEvent("op-001");                       // evento canónico del contrato
            String envelope = pushBody(mapper, e, "msg-001");              // serializado + Base64 + envelope
            ev.put("envelopeJson", envelope);                              // para re-POSTear / auditar
            ev.put("evento", evento(e));                                   // vista legible del evento
            ev.put("payloadHash", new PayloadHasher().hash(e.payload()));  // hash que verá la BD
            PlanInfo plan = plan(e);                                       // plan que emitirán Gobernador→Traductor
            ev.put("gobernador", plan.governador());
            ev.put("traductor", plan.sql());
            ProcessingOutcome o = workerService.handleRaw(envelope);       // POST /push simulado
            finalizar(ev, o, "SUCCEEDED", true);                           // assert del outcome (status + ack)
            snapshot(ev, store, "op-001");                                 // estado final + pago + cuarentena
        } catch (Exception ex) {
            fallo(ev, ex);                                                 // nunca dejar morir la exportación
        }
    }

    private void caso2a_duplicadoIdempotente() {
        Map<String, Object> ev = escenario("caso2a", "Réplica idéntica → IDEMPOTENT",
                "Caso 2a", "IDEMPOTENT", true, true, "02-caso1-replica-IDEMPOTENT");
        try {
            SyntheticEvent e = validEvent("op-001");
            String envelope = pushBody(mapper, e, "msg-002");              // mismo op-001 y mismo payload
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);
            workerService.handleRaw(envelope);                             // 1er POST → SUCCEEDED (no es el caso 2a)
            ProcessingOutcome o = workerService.handleRaw(envelope);       // 2do POST = el caso 2a → IDEMPOTENT
            ev.put("gobernador", "NO INVOCADO (respuesta inmediata por PAYLOAD_HASH idéntico)");
            ev.put("traductor", "-");
            finalizar(ev, o, "IDEMPOTENT", true);
            snapshot(ev, store, "op-001");
            ev.put("nota", "Primer POST → SUCCEEDED (mismo caso1 sobre estado limpio); este es el reenvío de Pub/Sub con la misma carga.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso2b_inFlightRetryable() {
        Map<String, Object> ev = escenario("caso2b", "Evento en vuelo → RETRYABLE (NACK)",
                "Caso 2b", "RETRYABLE", false, false, null);
        try {
            SyntheticEvent e = validEvent("op-flight");
            String envelope = pushBody(mapper, e, "msg-flight");
            String hash = new PayloadHasher().hash(e.payload());
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            // Pre-sembra un registro PROCESSING (= que otro proceso ya lo está procesando).
            store.update(new OperationState("op-flight", e.eventId(), e.traceId(), "msg-flight", hash,
                    OperationStatus.PROCESSING, 1, null, null, null));
            ProcessingOutcome o = workerService.handleRaw(envelope);       // choca con el en-vuelo → NACK
            ev.put("gobernador", "NO INVOCADO (evento en vuelo, NACK para redelivery)");
            ev.put("traductor", "-");
            finalizar(ev, o, "RETRYABLE", false);                          // ack=false → HTTP 500 en vivo
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
            SyntheticEvent e1 = validEvent("op-001");
            workerService.handleRaw(pushBody(mapper, e1, "msg-001"));      // op-001 ya procesado
            // Segundo evento con el MISMO operationId pero distinto payload (regla Idempotency-Key del LNB).
            SyntheticEvent e2 = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-001", "SYNTHETIC_PAYMENT_REQUESTED",
                    "op-001", "trace-001", "2026-09-07T00:00:00Z", "INSERT", "synthetic_payment",
                    new SyntheticPayload("claim-002", new BigDecimal("199.99"), "2026-09-08", "Pedro Ruiz"));
            String envelope = pushBody(mapper, e2, "msg-002");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e2));
            ev.put("payloadHash", new PayloadHasher().hash(e2.payload()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (colisión de PK detectada en la reserva)");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "op-001");
            ev.put("nota", "operationId reciclado con datos distintos = PK collision → cuarentena, no redelivery.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso3_prefiltroRejected() {
        Map<String, Object> ev = escenario("caso3", "Entidad no autorizada → REJECTED (pre-filtro)",
                "Caso 3 · pre-filtro del catálogo", "REJECTED", true, true, "03-caso3-REJECTED");
        try {
            // Entidad "synthetic_payroll_secret" NO está en la whitelist del catálogo.
            SyntheticEvent e = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-003", "SYNTHETIC_PAYMENT_REQUESTED",
                    "op-rej-3", "trace-003", "2026-09-07T00:00:00Z", "INSERT", "synthetic_payroll_secret",
                    new SyntheticPayload("claim-003", new BigDecimal("5.00"), "2026-09-07", "Nomina Oculta"));
            String envelope = pushBody(mapper, e, "msg-003");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.payload()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (pre-filtro del catálogo; ver BranchingCoverageTest caso3)");
            ev.put("traductor", "-");
            finalizar(ev, o, "REJECTED", true);
            snapshot(ev, store, "op-rej-3");
            ev.put("nota", "El pre-filtro del Worker rechaza sin invocar a Vertex (Gobernador), a diferencia del paso 9.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso3b_operacionNoPermitida() {
        Map<String, Object> ev = escenario("caso3b", "Operación no permitida (DELETE) → REJECTED",
                "Caso 3b", "REJECTED", true, true, "04-caso3b-REJECTED");
        try {
            // Misma entidad permitida (synthetic_payment) pero con OPERACIÓN (DELETE) fuera de la whitelist.
            SyntheticEvent e = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-003b", "SYNTHETIC_PAYMENT_REQUESTED",
                    "op-del-3", "trace-003b", "2026-09-07T00:00:00Z", "DELETE", "synthetic_payment",
                    new SyntheticPayload("claim-003b", new BigDecimal("5.00"), "2026-09-07", "Ana Gomez"));
            String envelope = pushBody(mapper, e, "msg-003b");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.payload()));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "NO INVOCADO (operación DELETE fuera de la whitelist del catálogo)");
            ev.put("traductor", "-");
            finalizar(ev, o, "REJECTED", true);
            snapshot(ev, store, "op-del-3");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso4_governorAlucinaDlq() {
        Map<String, Object> ev = escenario("caso4", "Gobernador alucina tabla → DLQ",
                "Caso 4 · validateGovernor", "DLQ_QUARANTINED", true, false, null);
        try {
            SyntheticEvent e = validEvent("op-hall");
            String envelope = pushBody(mapper, e, "msg-hall");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.payload()));
            // Inyecta al Gobernador una alucinación: aprueba pero apunta a una tabla inexistente.
            governor.setOverride(evento -> new GovernorContract(
                    "CONTRACT_SYNTHETIC_V0", GovernorDecision.APPROVED,
                    evento.operationId(), evento.traceId(), "Operación validada correctamente",
                    "TABLA_INVENTADA",
                    catalog.requiredFields(evento.entity()),
                    catalog.fieldMapping(evento.entity()),
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
            SyntheticEvent e = validEvent("op-doubt");
            String hash = new PayloadHasher().hash(e.payload());
            String envelope = pushBody(mapper, e, "msg-doubt");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            // Simula el crash después del commit: fila en SYNTHETIC_PAYMENTS + estado PROCESSING.
            store.seedDoubtful("op-doubt", hash, hash, "SYNTHETIC_PAYMENTS",
                    List.of("claim-001", "150.00", "2026-09-07", "Ana Gomez", "op-doubt", "trace-001", "evt-001", hash));
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
            SyntheticEvent e = validEvent("op-retry");
            String hash = new PayloadHasher().hash(e.payload());
            String envelope = pushBody(mapper, e, "msg-retry");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            // Estado previo: la operación quedó RETRYABLE (por un NACK de canal). El redelivery
            // debe retomarla: PROCESSING → pipeline → SUCCEEDED + pago.
            store.reserve(new OperationState("op-retry", e.eventId(), e.traceId(), "msg-retry", hash,
                    OperationStatus.PROCESSING, 1, null, null, null));
            store.update(store.get("op-retry").withStatus(OperationStatus.RETRYABLE));
            ProcessingOutcome o = workerService.handleRaw(envelope);
            PlanInfo plan = plan(e);
            ev.put("gobernador", plan.governador());
            ev.put("traductor", plan.sql());
            finalizar(ev, o, "SUCCEEDED", true);
            snapshot(ev, store, "op-retry");
            ev.put("nota", "redelivery recibe PROCESSING; dueño pendiente → reproceso con estado PROMOVER a SUCCEEDED.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso5r3_inDoubtHashDistintoDlq() {
        Map<String, Object> ev = escenario("caso5-r3", "in-doubt con otro hash → DLQ",
                "Caso 5 · Rama 3", "DLQ_QUARANTINED", true, false, null);
        try {
            SyntheticEvent e = validEvent("op-bad");
            String hash = new PayloadHasher().hash(e.payload());
            String envelope = pushBody(mapper, e, "msg-bad");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            // Fila de pago existente con un PAYLOAD_HASH DISTINTO: BD inconsistente → cuarentena.
            store.seedDoubtful("op-bad", hash, "2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae",
                    "SYNTHETIC_PAYMENTS", List.of("x"));
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
            // Envelope con data inválida: ni siquiera es Base64 → DLQ en el decode.
            String envelope = envelopeRaw(mapper, "!!not-valid-base64!!", "msg-b64");
            ev.put("envelopeJson", envelope);
            ev.put("evento", "-");
            ev.put("payloadHash", "-");
            ProcessingOutcome o = workerService.handleRaw(envelope);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            finalizar(ev, o, "DLQ_QUARANTINED", true);
            snapshot(ev, store, "-");                                      // sin operationId identificable
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void caso6_jsonMalformadoDlq() {
        Map<String, Object> ev = escenario("caso6-json", "JSON del evento malformado → DLQ",
                "Caso 6", "DLQ_QUARANTINED", true, true, "06-caso6-json-DLQ");
        try {
            // Base64 válido pero su contenido ("{") no es JSON parseable → DLQ al decodificar.
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
        Map<String, Object> ev = escenario("caso6-faltan", "Faltan operationId y payload → DLQ",
                "Caso 6", "DLQ_QUARANTINED", true, true, "07-caso6-faltan-DLQ");
        try {
            // Evento sintácticamente JSON pero sin operationId/traceId/payload → DLQ estructural
            // (falla la validación de esquema ANTES de tocar la reserva atómica).
            SyntheticEvent e = new SyntheticEvent("CONTRACT_SYNTHETIC_V0", "evt-006", "SYNTHETIC_PAYMENT_REQUESTED",
                    null, null, null, "INSERT", "synthetic_payment", null);
            String envelope = pushBody(mapper, e, "msg-faltan");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
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

    private void paso9_governorRechazado() {
        Map<String, Object> ev = escenario("paso9", "Gobernador rechaza por regla de negocio → REJECTED",
                "Paso 9 del contrato", "REJECTED", true, false, null);
        try {
            SyntheticEvent e = validEvent("op-rej-9");
            String envelope = pushBody(mapper, e, "msg-rej9");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", new PayloadHasher().hash(e.payload()));
            // Gobernador que SÍ es invocado y rechaza por regla de negocio (no por nombre de tabla).
            // Es la rama REJECTED del paso 9: distinta del pre-filtro (caso 3) y NO va a DLQ.
            governor.setOverride(evento -> new GovernorContract(
                    "CONTRACT_SYNTHETIC_V0", GovernorDecision.REJECTED,
                    evento.operationId(), evento.traceId(), "Regla de negocio: límite de monto excedido",
                    "SYNTHETIC_PAYMENTS",
                    catalog.requiredFields(evento.entity()),
                    catalog.fieldMapping(evento.entity()),
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
            SyntheticEvent e = validEvent("op-xtr");
            String envelope = pushBody(mapper, e, "msg-xtr");
            ev.put("envelopeJson", envelope);
            ev.put("evento", evento(e));
            ev.put("payloadHash", HASH_OP001);

            // Pipeline AISLADO (no el contexto Spring): el DeterministicTranslator real jamás
            // produciría COLUMNA_INVENTADA, así que se mockea un Traductor "alucinado".
            JsonMapper jm = JsonMapper.builder().build();                  // mapper Jackson 3 independiente
            Catalog cat = new Catalog(jm);                                // catálogo con las reglas reales
            InMemoryStateStore isolado = new InMemoryStateStore();         // StateStore de este escenario
            Governor gov = mock(Governor.class);                           // Gobernador fijo APPROVED
            Translator tr = mock(Translator.class);                        // Traductor que inventa columnas

            when(gov.decide(any())).thenReturn(new GovernorContract(      // contrato de Gobernador válido
                    "CONTRACT_SYNTHETIC_V0", GovernorDecision.APPROVED,
                    e.operationId(), e.traceId(), "Operación validada correctamente",
                    "SYNTHETIC_PAYMENTS", cat.requiredFields(e.entity()), cat.fieldMapping(e.entity()),
                    Map.of(), Catalog.CATALOG_VERSION, List.of()));
            // SQL con 9 columnas: la última (COLUMNA_INVENTADA) NO está en la whitelist.
            String inventado = "INSERT INTO SYNTHETIC_PAYMENTS (COD_RECLAMO, MONTO, FECHA_OPER, BENEFICIARIO, "
                    + "OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH, COLUMNA_INVENTADA) VALUES (?,?,?,?,?,?,?,?,?)";
            when(tr.translate(any(), any(), any())).thenReturn(
                    new TranslatorResult("TRANSLATED", inventado,
                            List.of("claim-001", "150.00", "2026-09-07", "Ana Gomez",
                                    "op-xtr", "trace-001", "evt-001", HASH_OP001, "xyz"),
                            inventado.replace("? ?", "x")));
            WorkerService iso = new WorkerService(jm, new PayloadHasher(), new StructuralValidator(),
                    cat, gov, tr, isolado);

            ProcessingOutcome o = iso.handleRaw(envelope);
            ev.put("gobernador", "APPROVED · plan válido");
            ev.put("traductor", "sql_template con COLUMNA_INVENTADA (mock aislado; el DeterministicTranslator real no la produce)");
            finalizar(ev, o, "TRANSLATION_ERROR", true);
            snapshot(ev, isolado, "op-xtr");
            ev.put("nota", "Guard validateTranslation: tabla exacta, 8 parámetros y columnas ⊆ whitelist. Ver BranchingCoverageTest caso_translatorDevuelveColumnaInventadaEsTranslationError.");
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    private void hasher_contrato() {
        Map<String, Object> ev = escenario("hasher", "PAYLOAD_HASH canónico del contrato",
                "Contrato §2.1", "PASS", true, false, null);
        try {
            SyntheticEvent e = validEvent("op-hash");
            String hash = new PayloadHasher().hash(e.payload());          // función pura: sin HTTP
            ev.put("evento", evento(e));
            ev.put("payloadHash", hash);
            ev.put("gobernador", "-");
            ev.put("traductor", "-");
            boolean ok = HASH_OP001.equals(hash);                          // debe ser EXACTAMENTE el del contrato
            ev.put("resultado", ok ? "PASS" : "FAIL");
            ev.put("obtenidoStatus", hash);
            ev.put("esperadoStatus", "8c0f97a3…389d6 (ver contrato §2.1)");
            ev.put("httpTexto", "N/A (función pura, sin HTTP)");
            if (!ok) {
                ev.put("detalle", "hash != 8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6");
            }
        } catch (Exception ex) {
            fallo(ev, ex);
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Abre un expediente de escenario Y garantiza estado limpio: como la matriz corre en un solo
     * @Test secuencial, aquí se resetea StateStore y el override del Gobernador para que el
     * override de un caso (p.ej. Caso 4 → TABLA_INVENTADA) NUNCA contamine al siguiente.
     */
    private Map<String, Object> escenario(String id, String titulo, String casoRef, String esperado,
                                          boolean ackEsperado, boolean liveSafe, String liveName) {
        store.reset();
        governor.resetOverride();
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("id", id);
        ev.put("titulo", titulo);
        ev.put("casoRef", casoRef);
        ev.put("esperadoStatus", esperado);
        ev.put("ackEsperado", ackEsperado);
        ev.put("liveSafe", liveSafe);
        if (liveSafe) {
            ev.put("liveName", liveName);                                  // nombre del archivo reusable en live
        }
        EVIDENCIAS.add(ev);                                                // sale en la evidencia (orden estable)
        return ev;
    }

    /**
     * Valida el outcome del pipeline contra lo esperado y lo deja escrito en el expediente.
     * no se lanza excepción aquí: si FALLA queda "resultado"=FAIL y el assert final lo atrapa.
     */
    private void finalizar(Map<String, Object> ev, ProcessingOutcome o, String esperado, boolean ackEsperado) {
        ev.put("obtenidoStatus", o.status().name());
        ev.put("httpTexto", (o.ack() ? "200" : "500") + " (ack=" + o.ack() + ")");  // ACK→200, NACK→500
        boolean ok = o.status().name().equals(esperado) && o.ack() == ackEsperado;
        ev.put("resultado", ok ? "PASS" : "FAIL");
        if (!ok) {
            ev.put("detalle", "esperado " + esperado + " ack=" + ackEsperado
                    + "; obtenido " + o.status() + " ack=" + o.ack());
        }
    }

    // Registra una excepción del escenario como FAIL sin abortar la exportación de evidencia.
    private void fallo(Map<String, Object> ev, Exception ex) {
        ev.put("resultado", "FAIL");
        ev.put("obtenidoStatus", "EXCEPTION");
        ev.put("httpTexto", "-");
        ev.put("detalle", ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    // Captura el estado del "store" tras el escenario: WORKER_OPERATION_STATE + SYNTHETIC_PAYMENTS + DLQ.
    private void snapshot(Map<String, Object> ev, InMemoryStateStore st, String operationId) {
        OperationState s = st.get(operationId);
        ev.put("estadoFinal", s == null ? "-" : estadoFila(s));
        PaymentRow row = st.findPayment(operationId);
        ev.put("paymentPersisted", row != null);                           // ¿se persistió el pago?
        ev.put("cuarentena", st.quarantineAudit());                        // auditoría de DLQ del mock
    }

    // Línea legible de una fila de WORKER_OPERATION_STATE para el MD de la evidencia.
    private String estadoFila(OperationState s) {
        return "status=" + s.status()
                + " decision=" + (s.governorDecision() == null ? "-" : s.governorDecision())
                + " target=" + (s.targetTable() == null ? "-" : s.targetTable())
                + " attempt=" + s.attemptCount()
                + (s.errorReason() == null ? "" : " error=" + s.errorReason());
    }

    // Vista legible del evento (campos del contrato) para la evidencia; los montos van como string.
    private Map<String, Object> evento(SyntheticEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contract_version", e.contract_version());
        m.put("event_id", e.eventId());
        m.put("event_type", e.eventType());
        m.put("operationId", e.operationId());
        m.put("traceId", e.traceId());
        m.put("occurred_at", e.occurredAt());
        m.put("operation", e.operation());
        m.put("entity", e.entity());
        if (e.payload() != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("cod_reclamo", e.payload().claimId());
            p.put("monto", String.valueOf(e.payload().amount()));
            p.put("fecha_oper", e.payload().operationDate());
            p.put("beneficiario", e.payload().beneficiary());
            m.put("payload", p);
        } else {
            m.put("payload", null);
        }
        return m;
    }

    // Calcula qué habría producido Gobernador + Traductor para este evento (evidencia del plan de escritura).
    private PlanInfo plan(SyntheticEvent e) throws Exception {
        String hash = new PayloadHasher().hash(e.payload());
        GovernorContract g = governor.decide(e);
        if (g.decision() != GovernorDecision.APPROVED) {
            return new PlanInfo("REJECTED · " + g.reason(), "-");
        }
        TranslatorResult r = translator.translate(e, g, hash);
        return new PlanInfo("APPROVED · target=" + g.target_table(), r.sql_template());
    }

    private record PlanInfo(String governador, String sql) {
    }
}