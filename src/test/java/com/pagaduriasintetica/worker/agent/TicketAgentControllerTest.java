package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.tool.FixtureTicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketToolResponse;
import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Contrato HTTP del agente: estados de negocio y errores tipados.
 * Usa el fixture sintetico; no toca el WS real de Carlos.
 */
class TicketAgentControllerTest {

    private static final String URL = "/api/v1/agent/ticket-query";

    private static MockMvc mvc(TicketQueryTool tool) {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), tool);
        return MockMvcBuilders
                .standaloneSetup(new TicketAgentController(agent))
                .setControllerAdvice(new TicketAgentAdvice())
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
                .andExpect(jsonPath("$.prize.amount").value("150.00"))
                .andExpect(jsonPath("$.provenance").value("SYNTHETIC"));
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
                .andExpect(jsonPath("$.prize").doesNotExist());
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
                .andExpect(jsonPath("$.prize").doesNotExist());
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
}
