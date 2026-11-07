package com.pagaduriasintetica.worker.contract.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape publico de {@link TicketQueryResult}: el agente no debe emitir {@code null}
 * donde el WS omite el campo, y debe exponer {@code source} cuando el WS lo declara.
 */
class TicketQueryResultSerializationTest {

    private final ObjectMapper json = new ObjectMapper();

    private static final TicketKey KEY =
            new TicketKey("LNB", "2026-10-15", "0012345678", "A", "5");

    @Test
    void found_serializa_premio_estado_y_source() throws Exception {
        TicketQueryResult result = new TicketQueryResult(
                "trace-found", KEY, TicketLookupStatus.FOUND,
                new PrizeInfo("50.00", "USD", "CASH"), "PENDING",
                TicketProvenance.SYNTHETIC, "SEED", "Boleto encontrado y premiado");

        String body = json.writeValueAsString(result);

        assertTrue(body.contains("\"prize\""));
        assertTrue(body.contains("\"collectionStatus\":\"PENDING\""));
        assertTrue(body.contains("\"source\":\"SEED\""));
    }

    @Test
    void not_awarded_omite_premio_estado_y_source_nulos() throws Exception {
        TicketQueryResult result = new TicketQueryResult(
                "trace-na", KEY, TicketLookupStatus.NOT_AWARDED,
                null, null, TicketProvenance.SYNTHETIC, null, "Boleto no premiado");

        String body = json.writeValueAsString(result);

        assertFalse(body.contains("\"prize\""));
        assertFalse(body.contains("\"collectionStatus\""));
        assertFalse(body.contains("\"source\""));
        assertTrue(body.contains("\"result\":\"NOT_AWARDED\""));
    }

    @Test
    void not_found_omite_premio_estado_y_source_nulos() throws Exception {
        TicketQueryResult result = new TicketQueryResult(
                "trace-nf", KEY, TicketLookupStatus.NOT_FOUND,
                null, null, TicketProvenance.SYNTHETIC, null, "Boleto no encontrado");

        String body = json.writeValueAsString(result);

        assertFalse(body.contains("\"prize\""));
        assertFalse(body.contains("\"collectionStatus\""));
        assertFalse(body.contains("\"source\""));
        assertTrue(body.contains("\"result\":\"NOT_FOUND\""));
    }

    @Test
    void source_sybase_se_serializa_junto_con_provenance_real() throws Exception {
        TicketQueryResult result = new TicketQueryResult(
                "trace-real", KEY, TicketLookupStatus.FOUND,
                new PrizeInfo("50.00", "USD", "CASH"), "PENDING",
                TicketProvenance.REAL, "SYBASE", "Boleto encontrado y premiado");

        String body = json.writeValueAsString(result);

        assertTrue(body.contains("\"provenance\":\"REAL\""));
        assertTrue(body.contains("\"source\":\"SYBASE\""));
    }
}
