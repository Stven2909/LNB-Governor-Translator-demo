package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.agent.TicketAgent;
import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import com.pagaduriasintetica.worker.contract.ticket.TicketLookupStatus;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Flujo completo agente -> adaptador HTTP -> WS simulado.
 * MOCK: Carlos aun NO ha entregado el WS real; este test valida el modo HTTP
 * contra MockTicketWsServer y no usa datos ni endpoints de produccion.
 */
class TicketAgentHttpModeTest {

    private static final String FOUND_BODY = """
            {"found":true,"awarded":true,"ticketNumber":"0012345678",
             "prize":{"amount":"150.00","currency":"USD","category":"CASH"},
             "collectionStatus":"PENDING","provenance":"SYNTHETIC"}
            """;

    private static final String NOT_AWARDED_BODY = """
            {"found":true,"awarded":false,"ticketNumber":"0011111111",
             "prize":null,"collectionStatus":null,"provenance":"SYNTHETIC"}
            """;

    private static final String NOT_FOUND_BODY = """
            {"found":false,"awarded":false,"ticketNumber":"9999999999",
             "prize":null,"collectionStatus":null,"provenance":"SYNTHETIC"}
            """;

    private static TicketAgent agent(MockTicketWsServer ws, String timeout) {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setMode("HTTP");
        props.setBaseUrl(ws.baseUrl());
        props.setTimeout(timeout);
        return new TicketAgent(
                new DeterministicTicketModelClient(),
                new HttpTicketQueryTool(props));
    }

    @Test
    void flujo_completo_en_modo_http() throws Exception {
        // MOCK: WS simulado, no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer()) {
            TicketAgent agent = agent(ws, "2s");

            ws.respond(200, FOUND_BODY);
            TicketQueryResult found = agent.process(
                    new TicketKey("LNB", "2026-10-15", "0012345678", "A", "5"), "http-001");
            assertEquals(TicketLookupStatus.FOUND, found.result());
            assertEquals("150.00", found.prize().amount());
            assertEquals("http-001", found.traceId());

            ws.respond(200, NOT_AWARDED_BODY);
            TicketQueryResult notAwarded = agent.process(
                    new TicketKey("LNB", "2026-10-15", "0011111111", null, null), "http-002");
            assertEquals(TicketLookupStatus.NOT_AWARDED, notAwarded.result());
            assertNull(notAwarded.prize());

            ws.respond(200, NOT_FOUND_BODY);
            TicketQueryResult notFound = agent.process(
                    new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "http-003");
            assertEquals(TicketLookupStatus.NOT_FOUND, notFound.result());
            assertNull(notFound.prize());
        }
    }

    @Test
    void timeout_del_ws_simulado_se_propaga() throws Exception {
        // MOCK: el WS simulado tarda mas que app.ticket-agent.timeout.
        try (MockTicketWsServer ws = new MockTicketWsServer()
                .respond(200, FOUND_BODY)
                .delay(Duration.ofMillis(1500))) {
            TicketAgent agent = agent(ws, "300ms");
            assertThrows(TicketTimeoutException.class, () -> agent.process(
                    new TicketKey("LNB", "2026-10-15", "0012345678", null, null), "http-004"));
        }
    }
}
