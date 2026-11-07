package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketDataIntegrityException;
import com.pagaduriasintetica.worker.agent.exception.TicketDatabaseUnavailableException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsUnavailableException;
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
             "prize":{"amount":"50.00","currency":"USD","category":"CASH"},
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

    /** 404 con payload de negocio: el WS declara explicitamente que no encontro el boleto. */
    private static final String NOT_FOUND_404_BODY = """
            {"found":false,"awarded":false,"ticketNumber":"9999999999",
             "prize":null,"collectionStatus":null,"provenance":"REAL","source":"SYBASE"}
            """;

    /** Respuesta canonica de Carlos (E1): incluye source, databaseSource y message. */
    private static final String FOUND_CANONICAL_BODY = """
            {"found":true,"awarded":true,"ticketNumber":"0012345678",
             "prize":{"amount":"50.00","currency":"USD","category":"CASH"},
             "collectionStatus":"PENDING","provenance":"REAL","source":"SYBASE",
             "databaseSource":"Sybase ASE LNB DEV","message":"Boleto encontrado y premiado"}
            """;

    /** El contrato puede crecer; un campo nuevo no debe romper la deserializacion. */
    private static final String FOUND_CANONICAL_WITH_UNKNOWN_FIELD_BODY = """
            {"found":true,"awarded":true,"ticketNumber":"0012345678",
             "prize":{"amount":"50.00","currency":"USD","category":"CASH"},
             "collectionStatus":"PENDING","provenance":"REAL","source":"SYBASE",
             "databaseSource":"Sybase ASE LNB DEV","message":"Boleto encontrado y premiado",
             "campoFuturo":{"anidado":true}}
            """;

    /** El WS no declara procedencia: el adaptador debe usar la configurada, no inventarla. */
    private static final String FOUND_WITHOUT_PROVENANCE_BODY = """
            {"found":true,"awarded":true,"ticketNumber":"0012345678",
             "prize":{"amount":"50.00","currency":"USD","category":"CASH"},
             "collectionStatus":"PENDING"}
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
            assertEquals("50.00", response.prize().amount());
            assertEquals("SYNTHETIC", response.provenance());

            assertTrue(ws.lastRequestBody().contains("\"lotteryCode\":\"LNB\""));
            assertTrue(ws.lastRequestBody().contains("\"ticketNumber\":\"0012345678\""));
            assertTrue(ws.lastRequestBody().contains("\"series\":\"A\""));
            assertTrue(ws.lastRequestBody().contains("\"checkDigit\":\"5\""));
            assertEquals("req-found", ws.lastRequestId());
            assertEquals("req-found", ws.lastCorrelationId(),
                    "el mismo id debe viajar al WS por ambas cabeceras");
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
    void no_encontrado_404_con_payload_de_negocio_se_traduce_a_not_found() throws Exception {
        // MOCK: el WS responde 404 con un cuerpo que declara found=false; eso SI es NOT_FOUND.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(404, NOT_FOUND_404_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s"))
                    .query(new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "req-404");

            assertFalse(response.found());
            assertEquals("9999999999", response.ticketNumber());
            assertEquals("REAL", response.provenance());
            assertEquals("SYBASE", response.source());
        }
    }

    @Test
    void respuesta_canonica_con_source_y_databaseSource_se_parsea() throws Exception {
        // MOCK: shape canonico de Carlos, aun sin el WS real.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, FOUND_CANONICAL_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s")).query(KEY, "req-canonical");

            assertTrue(response.found());
            assertTrue(response.awarded());
            assertEquals("50.00", response.prize().amount());
            assertEquals("REAL", response.provenance());
            assertEquals("SYBASE", response.source());
            assertEquals("Sybase ASE LNB DEV", response.databaseSource());
            assertEquals("Boleto encontrado y premiado", response.message());
        }
    }

    @Test
    void campos_desconocidos_del_ws_no_rompen_la_deserializacion() throws Exception {
        // El contrato puede crecer: un campo nuevo no debe tumbar el agente.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, FOUND_CANONICAL_WITH_UNKNOWN_FIELD_BODY)) {
            TicketToolResponse response = tool(props(ws.baseUrl(), "2s")).query(KEY, "req-unknown");

            assertEquals("REAL", response.provenance());
            assertEquals("SYBASE", response.source());
        }
    }

    @Test
    void not_found_sin_payload_de_negocio_no_se_disfraza_de_no_encontrado() throws Exception {
        // MOCK: 404 por ruta inexistente con un cuerpo que no declara found. No puede parecer NOT_FOUND.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(404, "{}")) {
            TicketWsException error = assertThrows(TicketWsException.class,
                    () -> tool(props(ws.baseUrl(), "2s"))
                            .query(new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "req-404-vacio"));

            assertTrue(error.getMessage().contains("404"));
            assertTrue(error.getMessage().contains("payload"));
        }
    }

    @Test
    void not_found_con_cuerpo_vacio_se_reporta_como_ws_error() throws Exception {
        // MOCK: 404 sin cuerpo. Ambiguo por definicion: no se traduce.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(404, "")) {
            assertThrows(TicketWsException.class,
                    () -> tool(props(ws.baseUrl(), "2s"))
                            .query(new TicketKey("LNB", "2026-10-15", "9999999999", null, null), "req-404-sin-cuerpo"));
        }
    }

    @Test
    void provenance_ausente_usa_la_configurada_y_no_la_inventa() throws Exception {
        // MOCK: el WS no declara procedencia; mandan app.ticket-agent.provenance/source.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, FOUND_WITHOUT_PROVENANCE_BODY)) {
            TicketAgentProperties props = props(ws.baseUrl(), "2s");
            props.setProvenance("REAL");
            props.setSource("SYBASE");

            TicketToolResponse response = new HttpTicketQueryTool(props).query(KEY, "req-prov");

            assertEquals("REAL", response.provenance());
            assertEquals("SYBASE", response.source());
        }
    }

    @Test
    void source_configurada_no_se_inyecta_si_el_ws_declara_procedencia() throws Exception {
        // El payload manda: si declara provenance, no completamos source por conveniencia.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(200, FOUND_BODY)) {
            TicketAgentProperties props = props(ws.baseUrl(), "2s");
            props.setSource("SYBASE");

            TicketToolResponse response = new HttpTicketQueryTool(props).query(KEY, "req-src-declared");

            assertEquals("SYNTHETIC", response.provenance());
            assertNull(response.source());
        }
    }

    @Test
    void source_invalida_falla_al_construir() {
        TicketAgentProperties props = props("http://127.0.0.1:9090", "2s");
        props.setSource("OTRA");

        assertThrows(IllegalStateException.class, () -> new HttpTicketQueryTool(props));
    }

    @Test
    void provenance_invalida_falla_al_construir() {
        TicketAgentProperties props = props("http://127.0.0.1:9090", "2s");
        props.setProvenance("NO-EXISTE");

        assertThrows(IllegalStateException.class, () -> new HttpTicketQueryTool(props));
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
    void error_503_del_ws_se_preserva_como_database_unavailable() throws Exception {
        // MOCK: shape canonico del WS de Carlos ({"error":"..."}); no se degrada a 502.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(
                503, "{\"error\":\"DATABASE_UNAVAILABLE\",\"message\":\"sin pool\"}")) {
            TicketDatabaseUnavailableException error = assertThrows(TicketDatabaseUnavailableException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-503"));

            assertTrue(error.getMessage().contains("503"));
            assertTrue(error.getMessage().contains("DATABASE_UNAVAILABLE"));
        }
    }

    @Test
    void error_503_con_shape_code_antiguo_tambien_se_acepta() throws Exception {
        // Compatibilidad: algunos emisores usan "code" en lugar de "error".
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(
                503, "{\"code\":\"DATABASE_UNAVAILABLE\",\"message\":\"sin pool\"}")) {
            TicketDatabaseUnavailableException error = assertThrows(TicketDatabaseUnavailableException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-503-code"));

            assertTrue(error.getMessage().contains("DATABASE_UNAVAILABLE"));
        }
    }

    @Test
    void error_503_sin_codigo_conocido_conserva_el_503_sin_afirmar_la_causa() throws Exception {
        // Un 503 de un proxy no autoriza a decir DATABASE_UNAVAILABLE.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(
                503, "{\"error\":\"UPSTREAM_BUSY\",\"message\":\"proxy\"}")) {
            TicketWsUnavailableException error = assertThrows(TicketWsUnavailableException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-503-otro"));

            assertTrue(error.getMessage().contains("503"));
            assertTrue(error.getMessage().contains("UPSTREAM_BUSY"));
        }
    }

    @Test
    void error_503_sin_cuerpo_conserva_el_503_sin_codigo() throws Exception {
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(503, "")) {
            TicketWsUnavailableException error = assertThrows(TicketWsUnavailableException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-503-vacio"));

            assertTrue(error.getMessage().contains("503"));
        }
    }

    @Test
    void error_502_del_ws_se_preserva_como_data_integrity() throws Exception {
        // MOCK: shape canonico del WS de Carlos ({"error":"..."}).
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(
                502, "{\"error\":\"DATA_INTEGRITY_ERROR\",\"message\":\"premio nulo\"}")) {
            TicketDataIntegrityException error = assertThrows(TicketDataIntegrityException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-502"));

            assertTrue(error.getMessage().contains("502"));
            assertTrue(error.getMessage().contains("DATA_INTEGRITY_ERROR"));
        }
    }

    @Test
    void error_502_sin_codigo_conocido_se_reporta_como_ws_error() throws Exception {
        // 502 sin DATA_INTEGRITY_ERROR no es una inconsistencia confirmada.
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(
                502, "{\"error\":\"BAD_GATEWAY\",\"message\":\"proxy\"}")) {
            TicketWsException error = assertThrows(TicketWsException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-502-otro"));

            assertFalse(error instanceof TicketDataIntegrityException);
            assertTrue(error.getMessage().contains("502"));
            assertTrue(error.getMessage().contains("BAD_GATEWAY"));
        }
    }

    @Test
    void error_502_sin_cuerpo_se_reporta_como_ws_error() throws Exception {
        try (MockTicketWsServer ws = new MockTicketWsServer().respond(502, "")) {
            TicketWsException error = assertThrows(TicketWsException.class,
                    () -> tool(props(ws.baseUrl(), "2s")).query(KEY, "req-502-vacio"));

            assertFalse(error instanceof TicketDataIntegrityException);
            assertTrue(error.getMessage().contains("502"));
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
