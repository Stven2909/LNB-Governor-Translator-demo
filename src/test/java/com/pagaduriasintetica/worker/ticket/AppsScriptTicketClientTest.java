package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class AppsScriptTicketClientTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private AppsScriptTicketProperties properties;
    private AppsScriptTicketClient client;

    @BeforeEach
    void setUp() {
        properties = new AppsScriptTicketProperties(null);
        properties.setSource("APPS_SCRIPT");
        properties.getAppsScript().setUrl("https://script.google.com/macros/s/test-script/exec");

        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        client = new AppsScriptTicketClient(restClientBuilder.build(), properties);
    }

    @Test
    @DisplayName("Caso 1: LOTRA ganador con premio $100.00 USD, UNPAID, SYNTHETIC, APPS_SCRIPT")
    void caso1_lotraGanador100UsdUnpaid() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": true,
                  "ticketNumber": "9911022000001",
                  "prize": {
                    "amount": "100.00",
                    "currency": "USD",
                    "category": "CASH"
                  },
                  "collectionStatus": "UNPAID",
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-case-1"
                }
                """;

        mockServer.expect(requestTo(containsString("lotteryCode=LOTRA")))
                .andExpect(requestTo(containsString("drawNumber=QA26-1102")))
                .andExpect(requestTo(containsString("ticketNumber=9911022000001")))
                .andExpect(requestTo(containsString("requestId=corr-case-1")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-case-1");

        mockServer.verify();
        assertNotNull(res);
        assertTrue(res.found());
        assertTrue(res.awarded());
        assertEquals("9911022000001", res.ticketNumber());
        assertNotNull(res.prize());
        assertEquals("100.00", res.prize().amount());
        assertEquals("USD", res.prize().currency());
        assertEquals("CASH", res.prize().category());
        assertEquals("UNPAID", res.collectionStatus());
        assertEquals("SYNTHETIC", res.provenance());
        assertEquals("APPS_SCRIPT", res.source());
        assertEquals(AppsScriptTicketClient.DATABASE_SOURCE_LABEL, res.databaseSource());
    }

    @Test
    @DisplayName("Caso 2: LOTIN ganador con premio $50.00 USD, UNPAID, SYNTHETIC, APPS_SCRIPT")
    void caso2_lotinGanador50UsdUnpaid() {
        TicketKey key = new TicketKey("LOTIN", "QB26-1102", "9911022000002", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": true,
                  "ticketNumber": "9911022000002",
                  "prize": {
                    "amount": "50.00",
                    "currency": "USD",
                    "category": "CASH"
                  },
                  "collectionStatus": "UNPAID",
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-case-2"
                }
                """;

        mockServer.expect(requestTo(containsString("lotteryCode=LOTIN")))
                .andExpect(requestTo(containsString("drawNumber=QB26-1102")))
                .andExpect(requestTo(containsString("ticketNumber=9911022000002")))
                .andExpect(requestTo(containsString("requestId=corr-case-2")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-case-2");

        mockServer.verify();
        assertTrue(res.found());
        assertTrue(res.awarded());
        assertEquals("9911022000002", res.ticketNumber());
        assertEquals("50.00", res.prize().amount());
        assertEquals("UNPAID", res.collectionStatus());
        assertEquals("SYNTHETIC", res.provenance());
        assertEquals("APPS_SCRIPT", res.source());
    }

    @Test
    @DisplayName("Caso 3: LOTRA ganador con estado PAID y monto $120.00 USD")
    void caso3_lotraGanador120UsdPaid() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000003", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": true,
                  "ticketNumber": "9911022000003",
                  "prize": {
                    "amount": "120.00",
                    "currency": "USD",
                    "category": "CASH"
                  },
                  "collectionStatus": "PAID",
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-case-3"
                }
                """;

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000003")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-case-3");

        mockServer.verify();
        assertTrue(res.found());
        assertTrue(res.awarded());
        assertEquals("120.00", res.prize().amount());
        assertEquals("PAID", res.collectionStatus());
        assertEquals("APPS_SCRIPT", res.source());
    }

    @Test
    @DisplayName("Caso 4: LOTIN encontrado pero no premiado (awarded=false, prize=null, collectionStatus=null)")
    void caso4_lotinNoPremiado() {
        TicketKey key = new TicketKey("LOTIN", "QB26-1102", "9911022000004", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": false,
                  "ticketNumber": "9911022000004",
                  "prize": null,
                  "collectionStatus": null,
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-case-4"
                }
                """;

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000004")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-case-4");

        mockServer.verify();
        assertTrue(res.found());
        assertFalse(res.awarded());
        assertEquals("9911022000004", res.ticketNumber());
        assertNull(res.prize());
        assertNull(res.collectionStatus());
        assertEquals("SYNTHETIC", res.provenance());
        assertEquals("APPS_SCRIPT", res.source());
    }

    @Test
    @DisplayName("Caso 5: Boleto no encontrado (found=false, awarded=false, prize=null)")
    void caso5_boletoNoEncontrado() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9999999999999", null, null);

        String json = """
                {
                  "found": false,
                  "awarded": false,
                  "ticketNumber": "9999999999999",
                  "prize": null,
                  "collectionStatus": null,
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-case-5"
                }
                """;

        mockServer.expect(requestTo(containsString("ticketNumber=9999999999999")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-case-5");

        mockServer.verify();
        assertFalse(res.found());
        assertFalse(res.awarded());
        assertEquals("9999999999999", res.ticketNumber());
        assertNull(res.prize());
        assertNull(res.collectionStatus());
        assertEquals("SYNTHETIC", res.provenance());
        assertEquals("APPS_SCRIPT", res.source());
    }

    @Test
    @DisplayName("Refinamiento 1: Normalización LOTÍN -> LOTIN únicamente en query param de salida")
    void normalizacion_lotinConTildeSeNormalizaSoloHaciaUpstream() {
        TicketKey key = new TicketKey("LOTÍN", "QB26-1102", "9911022000002", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": true,
                  "ticketNumber": "9911022000002",
                  "prize": {
                    "amount": "50.00",
                    "currency": "USD",
                    "category": "CASH"
                  },
                  "collectionStatus": "UNPAID",
                  "provenance": "SYNTHETIC",
                  "requestId": "corr-norm-001"
                }
                """;

        mockServer.expect(requestTo(containsString("lotteryCode=LOTIN")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        TicketLookupResponse res = client.findTicket(key, "corr-norm-001");

        mockServer.verify();
        assertTrue(res.found());
        assertTrue(res.awarded());
        assertEquals("9911022000002", res.ticketNumber());
    }

    @Test
    @DisplayName("Timeout upstream: lanza AppsScriptTimeoutException al exceder tiempo de espera")
    void timeoutUpstream_lanzaAppsScriptTimeoutException() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000001")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
                });

        assertThrows(AppsScriptTimeoutException.class, () -> client.findTicket(key, "corr-timeout-001"));
    }

    @Test
    @DisplayName("Fail-Closed: error HTTP 500 del upstream lanza AppsScriptException y nunca NOT_FOUND")
    void error500Upstream_lanzaAppsScriptException() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000001")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        assertThrows(AppsScriptException.class, () -> client.findTicket(key, "corr-error-500"));
    }

    @Test
    @DisplayName("Integridad: respuesta premiada sin prize lanza AppsScriptDataIntegrityException")
    void integridad_premiadoSinPrizeLanzaDataIntegrityException() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);

        String json = """
                {
                  "found": true,
                  "awarded": true,
                  "ticketNumber": "9911022000001",
                  "prize": null,
                  "collectionStatus": "UNPAID",
                  "provenance": "SYNTHETIC"
                }
                """;

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000001")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThrows(AppsScriptDataIntegrityException.class, () -> client.findTicket(key, "corr-integrity-001"));
    }

    @Test
    @DisplayName("Integridad: NOT_FOUND con prize lanza AppsScriptDataIntegrityException")
    void integridad_notFoundConPrizeLanzaDataIntegrityException() {
        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);

        String json = """
                {
                  "found": false,
                  "awarded": false,
                  "ticketNumber": "9911022000001",
                  "prize": { "amount": "10.00", "currency": "USD", "category": "CASH" },
                  "collectionStatus": null,
                  "provenance": "SYNTHETIC"
                }
                """;

        mockServer.expect(requestTo(containsString("ticketNumber=9911022000001")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThrows(AppsScriptDataIntegrityException.class, () -> client.findTicket(key, "corr-integrity-002"));
    }

    @Test
    @DisplayName("Fail-Fast: inicio con source=APPS_SCRIPT y URL vacía lanza IllegalStateException")
    void failFast_sourceAppsScriptConUrlVaciaLanzaExcepcion() {
        AppsScriptTicketProperties props = new AppsScriptTicketProperties(null);
        props.setSource("APPS_SCRIPT");
        props.getAppsScript().setUrl("");

        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateAndLog);
        assertTrue(ex.getMessage().contains("app.ticket.apps-script.url"));
    }
}
