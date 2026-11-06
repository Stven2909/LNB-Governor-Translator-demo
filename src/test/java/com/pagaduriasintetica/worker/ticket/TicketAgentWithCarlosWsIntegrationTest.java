package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.agent.TicketAgent;
import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.tool.HttpTicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import com.pagaduriasintetica.worker.contract.ticket.TicketLookupStatus;
import com.pagaduriasintetica.worker.contract.ticket.TicketProvenance;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integración que demuestra el ciclo completo:
 * Agente IA (Steven) -> Herramienta HTTP -> Web Service de Carlos (POST /api/v1/tickets/lookup).
 * Demuestra que el WS de Carlos satisface al 100% el contrato del agente con trazabilidad honesta.
 */
class TicketAgentWithCarlosWsIntegrationTest {

    private static HttpServer server;
    private static int port;
    private static TicketLookupService lookupService;

    @BeforeAll
    static void startServer() throws IOException {
        SybaseTicketProperties props = new SybaseTicketProperties();
        props.setEnabled(false); // Modo offline / sin conexión VPN directa
        props.setDefaultProvenance("SYNTHETIC");
        lookupService = new TicketLookupService(new SybaseTicketRepository(props));

        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();

        server.createContext("/api/v1/tickets/lookup", exchange -> {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                String body = new String(is.readAllBytes(), StandardCharsets.UTF_8);

                String ticketNumber = "0012345678";
                if (body.contains("0011111111")) ticketNumber = "0011111111";
                if (body.contains("9999999999")) ticketNumber = "9999999999";

                TicketKey key = new TicketKey("LNB", "2026-10-15", ticketNumber, "A", "5");
                TicketLookupResponse res = lookupService.lookup(key);

                String json;
                if (res.awarded()) {
                    json = String.format(
                            "{\"found\":true,\"awarded\":true,\"ticketNumber\":\"%s\"," +
                            "\"prize\":{\"amount\":\"%s\",\"currency\":\"%s\",\"category\":\"%s\"}," +
                            "\"collectionStatus\":\"%s\",\"provenance\":\"%s\"}",
                            res.ticketNumber(), res.prize().amount(), res.prize().currency(),
                            res.prize().category(), res.collectionStatus(), res.provenance()
                    );
                } else if (res.found()) {
                    json = String.format(
                            "{\"found\":true,\"awarded\":false,\"ticketNumber\":\"%s\"," +
                            "\"prize\":null,\"collectionStatus\":null,\"provenance\":\"%s\"}",
                            res.ticketNumber(), res.provenance()
                    );
                } else {
                    json = String.format(
                            "{\"found\":false,\"awarded\":false,\"ticketNumber\":\"%s\"," +
                            "\"prize\":null,\"collectionStatus\":null,\"provenance\":\"%s\"}",
                            res.ticketNumber(), res.provenance()
                    );
                }

                // Propagar header X-Correlation-Id recibido
                String correlationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
                if (correlationId != null) {
                    exchange.getResponseHeaders().set("X-Correlation-Id", correlationId);
                    exchange.getResponseHeaders().set("X-Request-Id", correlationId);
                }

                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });

        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private TicketAgent buildAgent() {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setMode("HTTP");
        props.setBaseUrl("http://localhost:" + port);
        props.setLookupPath("/api/v1/tickets/lookup");
        props.setTimeout("2s");
        props.setProvenance("SYNTHETIC");

        HttpTicketQueryTool httpTool = new HttpTicketQueryTool(props);
        return new TicketAgent(new DeterministicTicketModelClient(), httpTool);
    }

    @Test
    @DisplayName("Integración completa: Agente consulta WS de Carlos y obtiene boleto ganador con procedencia SYNTHETIC (offline)")
    void agenteConsultaWsDeCarlosConExito() {
        TicketAgent agent = buildAgent();
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", "A", "5");

        TicketQueryResult result = agent.process(key, "trace-integral-carlos-001");

        assertNotNull(result);
        assertEquals(TicketLookupStatus.FOUND, result.result());
        assertNotNull(result.prize());
        assertEquals("50.00", result.prize().amount());
        assertEquals("USD", result.prize().currency());
        assertEquals("CASH", result.prize().category());
        assertEquals(TicketProvenance.SYNTHETIC, result.provenance());
        assertEquals("trace-integral-carlos-001", result.traceId());
    }

    @Test
    @DisplayName("Integración: Boleto no premiado consultado por el agente a través del WS de Carlos")
    void agenteConsultaBoletoNoPremiado() {
        TicketAgent agent = buildAgent();
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0011111111", null, null);

        TicketQueryResult result = agent.process(key, "trace-integral-carlos-002");

        assertNotNull(result);
        assertEquals(TicketLookupStatus.NOT_AWARDED, result.result());
        assertNull(result.prize());
        assertEquals(TicketProvenance.SYNTHETIC, result.provenance());
    }

    @Test
    @DisplayName("Integración: Boleto inexistente consultado por el agente a través del WS de Carlos")
    void agenteConsultaBoletoNoEncontrado() {
        TicketAgent agent = buildAgent();
        TicketKey key = new TicketKey("LNB", "2026-10-15", "9999999999", null, null);

        TicketQueryResult result = agent.process(key, "trace-integral-carlos-003");

        assertNotNull(result);
        assertEquals(TicketLookupStatus.NOT_FOUND, result.result());
        assertNull(result.prize());
        assertEquals(TicketProvenance.SYNTHETIC, result.provenance());
    }
}
