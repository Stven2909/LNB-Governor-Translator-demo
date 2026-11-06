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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TicketLookupControllerTest {

    private MockMvc mockMvcWithSeed;
    private MockMvc mockMvcFailClosed;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // 1. Controller con seed habilitada para pruebas locales aisladas
        SybaseTicketProperties propsSeed = new SybaseTicketProperties();
        propsSeed.setEnabled(false);
        propsSeed.setSeedEnabled(true);
        propsSeed.setDefaultProvenance("SYNTHETIC");

        SybaseTicketRepository repoSeed = new SybaseTicketRepository(propsSeed);
        TicketLookupService serviceSeed = new TicketLookupService(repoSeed);
        TicketLookupController controllerSeed = new TicketLookupController(serviceSeed);
        this.mockMvcWithSeed = MockMvcBuilders.standaloneSetup(controllerSeed).build();

        // 2. Controller en modo Fail Closed (seed-enabled=false, pool inactive)
        SybaseTicketProperties propsFailClosed = new SybaseTicketProperties();
        propsFailClosed.setEnabled(false);
        propsFailClosed.setSeedEnabled(false);

        SybaseTicketRepository repoFailClosed = new SybaseTicketRepository(propsFailClosed);
        TicketLookupService serviceFailClosed = new TicketLookupService(repoFailClosed);
        TicketLookupController controllerFailClosed = new TicketLookupController(serviceFailClosed);
        this.mockMvcFailClosed = MockMvcBuilders.standaloneSetup(controllerFailClosed).build();
    }

    @Test
    @DisplayName("Fail Closed: responde 503 SERVICE_UNAVAILABLE si el pool no está activo y seed-enabled=false")
    void failClosed_devuelve503SiNoHayConexionNiSemilla() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "0012345678"
        );

        mockMvcFailClosed.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "trace-fail-closed-001")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Correlation-Id", "trace-fail-closed-001"))
                .andExpect(jsonPath("$.error").value("DATABASE_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").value("trace-fail-closed-001"));
    }

    @Test
    @DisplayName("Caso 1 LNB: Boleto ganador en modo seed devuelve $50.00 USD, SYNTHETIC, SEED y correlación")
    void caso1_boletoGanadorDevuelvePremio50Usd() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "0012345678",
                "series", "A",
                "checkDigit", "5"
        );

        mockMvcWithSeed.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "smoke-test-carlos-001")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "smoke-test-carlos-001"))
                .andExpect(header().string("X-Request-Id", "smoke-test-carlos-001"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.awarded").value(true))
                .andExpect(jsonPath("$.ticketNumber").value("0012345678"))
                .andExpect(jsonPath("$.prize.amount").value("50.00"))
                .andExpect(jsonPath("$.prize.currency").value("USD"))
                .andExpect(jsonPath("$.prize.category").value("CASH"))
                .andExpect(jsonPath("$.collectionStatus").value("PENDING"))
                .andExpect(jsonPath("$.provenance").value("SYNTHETIC"))
                .andExpect(jsonPath("$.source").value("SEED"));
    }

    @Test
    @DisplayName("Caso compatible: acepta payload envuelto en 'ticket' y ecoa X-Correlation-Id")
    void caso_aceptaPayloadEnvueltoEnTicket() throws Exception {
        Map<String, Object> body = Map.of(
                "ticket", Map.of(
                        "lotteryCode", "LOTRA",
                        "drawNumber", "G13PLAN26-LOTRA-01",
                        "ticketNumber", "0012345678"
                )
        );

        mockMvcWithSeed.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "smoke-test-carlos-002")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "smoke-test-carlos-002"))
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.awarded").value(true));
    }

    @Test
    @DisplayName("Boleto no premiado devuelve awarded=false y omite prize (Jackson NON_NULL)")
    void caso_boletoNoPremiadoDevuelveAwardedFalse() throws Exception {
        Map<String, Object> body = Map.of(
                "lotteryCode", "LNB",
                "drawNumber", "2026-10-15",
                "ticketNumber", "0011111111"
        );

        mockMvcWithSeed.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-not-awarded")
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "corr-not-awarded"))
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

        mockMvcWithSeed.perform(post("/api/v1/tickets/lookup")
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

        mockMvcWithSeed.perform(post("/api/v1/tickets/lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("Endpoint de estado del pool devuelve status de Sybase")
    void caso_statusPoolDevuelveInformacion() throws Exception {
        mockMvcWithSeed.perform(get("/api/v1/tickets/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCheck").exists());
    }
}
