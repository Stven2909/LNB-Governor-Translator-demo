package com.pagaduriasintetica.worker.agent;

import com.jayway.jsonpath.JsonPath;
import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.exception.TicketDataIntegrityException;
import com.pagaduriasintetica.worker.agent.exception.TicketDatabaseUnavailableException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsUnavailableException;
import com.pagaduriasintetica.worker.agent.tool.FixtureTicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketToolResponse;
import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contrato HTTP del agente: estados de negocio, errores tipados y eco de correlacion.
 * Usa el fixture sintetico; no toca el WS real de Carlos.
 */
class TicketAgentControllerTest {

    private static final String URL = "/api/v1/agent/ticket-query";

    private static MockMvc mvc(TicketQueryTool tool) {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), tool);
        return MockMvcBuilders
                .standaloneSetup(new TicketAgentController(agent))
                .setControllerAdvice(new TicketAgentAdvice())
                .addFilters(new CorrelationIdFilter())
                .build();
    }

    private static MockMvc mvc() {
        return mvc(new FixtureTicketQueryTool());
    }

    @Test
    void encontrado_devuelve_200_y_premio() throws Exception {
        mvc().perform(post(URL)
                        .header("X-Request-Id", "ctrl-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678","series":"A","checkDigit":"5"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("ctrl-001"))
                .andExpect(jsonPath("$.result").value("FOUND"))
                .andExpect(jsonPath("$.prize.amount").value("50.00"))
                .andExpect(jsonPath("$.prize.currency").value("USD"))
                .andExpect(jsonPath("$.prize.category").value("CASH"))
                .andExpect(jsonPath("$.collectionStatus").value("PENDING"))
                .andExpect(jsonPath("$.provenance").value("SYNTHETIC"))
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void source_seed_se_expone_en_la_respuesta() throws Exception {
        TicketQueryTool seed = (key, traceId) -> new TicketToolResponse(
                true, true, key.ticketNumber(),
                new PrizeInfo("50.00", "USD", "CASH"),
                "PENDING", "SYNTHETIC", "SEED", "Fixture autorizado", "Boleto encontrado y premiado");

        mvc(seed).perform(post(URL)
                        .header("X-Correlation-Id", "ctrl-source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("SEED"))
                .andExpect(content().string(containsString("\"source\":\"SEED\"")));
    }

    @Test
    void no_premiado_devuelve_200_sin_premio() throws Exception {
        mvc().perform(post(URL)
                        .header("X-Request-Id", "ctrl-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0011111111"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NOT_AWARDED"))
                .andExpect(jsonPath("$.prize").doesNotExist())
                .andExpect(content().string(not(containsString("\"prize\""))))
                .andExpect(content().string(not(containsString("\"collectionStatus\""))))
                .andExpect(content().string(not(containsString("\"source\""))));
    }

    @Test
    void no_encontrado_devuelve_200_sin_premio() throws Exception {
        mvc().perform(post(URL)
                        .header("X-Request-Id", "ctrl-003")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"9999999999"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NOT_FOUND"))
                .andExpect(jsonPath("$.prize").doesNotExist())
                .andExpect(content().string(not(containsString("\"prize\""))))
                .andExpect(content().string(not(containsString("\"collectionStatus\""))))
                .andExpect(content().string(not(containsString("\"source\""))));
    }

    @Test
    void entrada_invalida_devuelve_400() throws Exception {
        mvc().perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.errors[0]").value("ticket.lotteryCode: lotteryCode es obligatorio"));
    }

    @Test
    void timeout_del_ws_devuelve_504() throws Exception {
        TicketQueryTool lento = (key, traceId) -> {
            throw new TicketTimeoutException("Timeout del WS de tickets");
        };
        mvc(lento).perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("WS_TIMEOUT"));
    }

    @Test
    void respuesta_inconsistente_devuelve_502() throws Exception {
        TicketQueryTool inconsistente = (key, traceId) -> new TicketToolResponse(
                true, false, key.ticketNumber(),
                new PrizeInfo("999.00", "USD", "CASH"), null, "SYNTHETIC");
        mvc(inconsistente).perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVALID_RESPONSE"));
    }

    @Test
    void database_unavailable_del_ws_devuelve_503_y_conserva_el_codigo() throws Exception {
        TicketQueryTool sinBd = (key, traceId) -> {
            throw new TicketDatabaseUnavailableException("WS de tickets sin base de datos disponible (503 DATABASE_UNAVAILABLE)");
        };
        mvc(sinBd).perform(post(URL)
                        .header("X-Correlation-Id", "corr-503")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value("corr-503"))
                .andExpect(header().string("X-Correlation-Id", "corr-503"))
                .andExpect(header().string("X-Request-Id", "corr-503"));
    }

    @Test
    void ws_unavailable_sin_causa_declarada_devuelve_503_pero_no_afirma_database_unavailable() throws Exception {
        TicketQueryTool noDisponible = (key, traceId) -> {
            throw new TicketWsUnavailableException("WS de tickets no disponible (503 UPSTREAM_BUSY)");
        };
        mvc(noDisponible).perform(post(URL)
                        .header("X-Correlation-Id", "corr-503-generico")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WS_ERROR"))
                .andExpect(jsonPath("$.traceId").value("corr-503-generico"))
                .andExpect(header().string("X-Correlation-Id", "corr-503-generico"))
                .andExpect(header().string("X-Request-Id", "corr-503-generico"));
    }

    @Test
    void data_integrity_del_ws_devuelve_502_con_su_propio_codigo() throws Exception {
        TicketQueryTool inconsistente = (key, traceId) -> {
            throw new TicketDataIntegrityException("WS de tickets reporta inconsistencia de datos (502 DATA_INTEGRITY_ERROR)");
        };
        mvc(inconsistente).perform(post(URL)
                        .header("X-Correlation-Id", "corr-502-integridad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_ERROR"))
                .andExpect(jsonPath("$.traceId").value("corr-502-integridad"))
                .andExpect(header().string("X-Correlation-Id", "corr-502-integridad"))
                .andExpect(header().string("X-Request-Id", "corr-502-integridad"));
    }

    @Test
    void correlacion_se_conserva_en_entrada_invalida() throws Exception {
        mvc().perform(post(URL)
                        .header("X-Correlation-Id", "corr-400")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.traceId").value("corr-400"))
                .andExpect(header().string("X-Correlation-Id", "corr-400"))
                .andExpect(header().string("X-Request-Id", "corr-400"));
    }

    @Test
    void correlacion_se_conserva_en_timeout() throws Exception {
        TicketQueryTool lento = (key, traceId) -> {
            throw new TicketTimeoutException("Timeout del WS de tickets");
        };
        mvc(lento).perform(post(URL)
                        .header("X-Request-Id", "corr-504")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.traceId").value("corr-504"))
                .andExpect(header().string("X-Correlation-Id", "corr-504"))
                .andExpect(header().string("X-Request-Id", "corr-504"));
    }

    @Test
    void correlacion_se_conserva_en_error_del_ws() throws Exception {
        TicketQueryTool roto = (key, traceId) -> {
            throw new TicketWsException("WS caido");
        };
        mvc(roto).perform(post(URL)
                        .header("X-Correlation-Id", "corr-502")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("WS_ERROR"))
                .andExpect(jsonPath("$.traceId").value("corr-502"))
                .andExpect(header().string("X-Correlation-Id", "corr-502"))
                .andExpect(header().string("X-Request-Id", "corr-502"));
    }

    @Test
    void x_correlation_id_gana_sobre_x_request_id() throws Exception {
        mvc().perform(post(URL)
                        .header("X-Correlation-Id", "corr-gana")
                        .header("X-Request-Id", "request-pierde")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traceId").value("corr-gana"))
                .andExpect(header().string("X-Correlation-Id", "corr-gana"))
                .andExpect(header().string("X-Request-Id", "corr-gana"));
    }

    @Test
    void sin_cabeceras_se_genera_la_misma_correlacion_en_cabeceras_y_body() throws Exception {
        MvcResult result = mvc().perform(post(URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"ticket":{"lotteryCode":"LNB","drawNumber":"2026-10-15","ticketNumber":"0012345678"}}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String correlationId = result.getResponse().getHeader("X-Correlation-Id");
        assertNotNull(correlationId);
        assertEquals(correlationId, result.getResponse().getHeader("X-Request-Id"));
        assertEquals(correlationId, JsonPath.read(result.getResponse().getContentAsString(), "$.traceId"));
    }
}
