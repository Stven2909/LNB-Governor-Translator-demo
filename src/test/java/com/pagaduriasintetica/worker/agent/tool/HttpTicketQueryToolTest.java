package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pruebas del adaptador HTTP contra un WS SIMULADO (MockTicketWsServer).
 * Carlos aun NO ha entregado el WS real; estos casos fijan el contrato esperado.
 */
class HttpTicketQueryToolTest {

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

    private static final TicketKey KEY = new TicketKey("LNB", "2026-10-15", "0012345678", "A", "5");

    private static TicketAgentProperties props(String baseUrl, String timeout) {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setMode("HTTP");
        props.setBaseUrl(baseUrl);
        props.setTimeout(timeout);
        return props;
    }

    private static HttpTicketQueryTool tool(TicketAgentProperties props) {
        return new HttpTicketQueryTool(props);
    }

    @Test
    void encontrado_devuelve_payload_y_envia_la_clave() throws Exception {
        // MOCK: respuesta simulada; no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, FOUND_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s")).query(KEY, "req-found");

            assertTrue(response.found());
            assertTrue(response.awarded());
            assertEquals("0012345678", response.ticketNumber());
            assertEquals("150.00", response.prize().amount());
            assertEquals("SYNTHETIC", response.provenance());

            assertTrue(ws.lastRequestBody().contains("\"lotteryCode\":\"LNB\""));
            assertTrue(ws.lastRequestBody().contains("\"ticketNumber\":\"0012345678\""));
            assertTrue(ws.lastRequestBody().contains("\"series\":\"A\""));
            assertTrue(ws.lastRequestBody().contains("\"checkDigit\":\"5\""));
            assertEquals("req-found", ws.lastRequestId());
        }
    }

    @Test
    void no_premiado_se_devuelve_tal_cual() throws Exception {
        // MOCK: respuesta simulada; no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, NOT_AWARDED_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s"))
                    .query(new TicketKey("LNB", "2026-10-15", "0011111111", null, null), "req-na");

            assertTrue(response.found());
            assertFalse(response.awarded());
            assertNull(response.prize());
        }
    }

    @Test
    void no_encontrado_200_se_devuelve_tal_cual() throws Exception {
        // MOCK: respuesta simulada; no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, NOT_FOUND_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s"))
                    .query(new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "req-nf");

            assertFalse(response.found());
            assertFalse(response.awarded());
        }
    }

    @Test
    void no_encontrado_404_se_traduce_a_not_found() throws Exception {
        // MOCK: el contrato propuesto puede responder 404; se traduce a NOT_FOUND.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(404, "{}")) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s"))
                    .query(new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "req-404");

            assertFalse(response.found());
            assertEquals("9999999999", response.ticketNumber());
            assertEquals("REAL", response.provenance());
        }
    }

    @Test
    void error_500_del_ws_se_reporta_como_ws_error() throws Exception {
        // MOCK: respuesta simulada; no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(500, "{\"error\":\"boom\"}")) {
            TicketWsException error = assertThrows(TicketWsException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-500"));
            assertTrue(error.getMessage().contains("500"));
        }
    }

    @Test
    void timeout_de_lectura_se_reporta_como_timeout() throws Exception {
        // MOCK: el WS simulado tarda mas que app.ticket-agent.timeout.
        try (MockTicketWsServer ws = new MockTicketWsServer()
                .respond(200, FOUND_BODY)
                .delay(Duration.ofMillis(1500))) {
            assertThrows(TicketTimeoutException.class,
                    () -> tool(props(ws.baseUrl(), "300ms")).query(KEY, "req-timeout"));
        }
    }

    @Test
    void json_malformado_se_reporta_como_respuesta_invalida() throws Exception {
        // MOCK: respuesta simulada; no es el WS de Carlos.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, "{no-es-json")) {
            assertThrows(InvalidWsResponseException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-json"));
        }
    }

    @Test
    void conexion_rechazada_se_reporta_como_ws_error_no_timeout() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThrows(TicketWsException.class,
                () -> tool(props("http://127.0.0.1:" + closedPort, "1s")).query(KEY, "req-refused"));
    }

    @Test
    void timeout_invalido_falla_al_construir() {
        assertThrows(IllegalStateException.class,
                () -> tool(props("http://127.0.0.1:9090", "no-es-duracion")));
    }

    @Test
    void base_url_obligatoria_en_modo_http() {
        assertThrows(IllegalStateException.class,
                () -> tool(props("", "2s")));
    }
}
