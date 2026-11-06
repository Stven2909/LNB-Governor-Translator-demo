package com.pagaduriasintetica.worker.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketLookupControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SybaseTicketProperties props = new SybaseTicketProperties();
        props.setEnabled(false); // deshabilitado para test unitario rapido
        props.setDefaultProvenance("REAL");
        SybaseTicketRepository repo = new SybaseTicketRepository(props);
        TicketLookupService service = new TicketLookupService(repo);
        mockMvc = MockMvcBuilders.standaloneSetup(new TicketLookupController(service)).build();
    }

    @Test
    @DisplayName("Caso 1: Consulta de boleto ganador devuelve 200, premio de 50.00 USD y estado PENDING")
    void caso1_boletoGanadorDevuelvePremioReal() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "0012345678",
                "series", "A",
                "checkDigit", "5"
        );

        mockMvc.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "smoke-test-carlos-001")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.awarded").value(true))
                .andExpect(jsonPath("$.ticketNumber").value("0012345678"))
                .andExpect(jsonPath("$.prize.amount").value("50.00"))
                .andExpect(jsonPath("$.prize.currency").value("USD"))
                .andExpect(jsonPath("$.prize.category").value("CASH"))
                .andExpect(jsonPath("$.collectionStatus").value("PENDING"))
                .andExpect(jsonPath("$.provenance").value("REAL"));
    }

    @Test
    @DisplayName("Caso compatible: acepta payload envuelto en 'ticket'")
    void caso_aceptaPayloadEnvueltoEnTicket() throws Exception {
        Map<String, Object> body = Map.of(
                "ticket", Map.of(
                        "lotteryCode", "LOTRA",
                        "drawNumber", "G13PLAN26-LOTRA-01",
                        "ticketNumber", "0012345678"
                )
        );

        mockMvc.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "smoke-test-carlos-002")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.awarded").value(true))
                .andExpect(jsonPath("$.prize.amount").value("50.00"));
    }

    @Test
    @DisplayName("Boleto no premiado devuelve awarded=false y prize=null")
    void caso_boletoNoPremiadoDevuelveAwardedFalse() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "0011111111"
        );

        mockMvc.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.awarded").value(false))
                .andExpect(jsonPath("$.prize").doesNotExist());
    }

    @Test
    @DisplayName("Boleto no encontrado devuelve found=false")
    void caso_boletoNoEncontradoDevuelveFoundFalse() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "9999999999"
        );

        mockMvc.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(false))
                .andExpect(jsonPath("$.awarded").value(false));
    }

    @Test
    @DisplayName("Petición sin campos requeridos devuelve 400 INVALID_INPUT")
    void caso_datosIncompletosDevuelve400() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB"
        );

        mockMvc.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("Endpoint de estado del pool devuelve status de Sybase")
    void caso_statusPoolDevuelveInformacion() throws Exception {
        mockMvc.perform(get("/api/v1/tickets/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheck").exists());
    }
}
