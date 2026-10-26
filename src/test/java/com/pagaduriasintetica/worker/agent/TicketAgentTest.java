package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.client.TicketModelClient;
import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.tool.FixtureTicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketToolResponse;
import com.pagaduriasintetica.worker.contract.ticket.*;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TicketAgentTest {

    @Test
    void caso1_encontrado_y_premiado() {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", "A", "5");
        TicketQueryResult result = agent.process(key, "test-001");
        assertEquals(TicketLookupStatus.FOUND, result.result());
        assertNotNull(result.prize());
        assertEquals("150.00", result.prize().amount());
        assertEquals("test-001", result.traceId());
    }

    @Test
    void caso2_boleto_no_encontrado() {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "9999999999", null, null);
        TicketQueryResult result = agent.process(key, "test-002");
        assertEquals(TicketLookupStatus.NOT_FOUND, result.result());
        assertNull(result.prize());
    }

    @Test
    void caso3_boleto_no_premiado() {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0011111111", null, null);
        TicketQueryResult result = agent.process(key, "test-003");
        assertEquals(TicketLookupStatus.NOT_AWARDED, result.result());
        assertNull(result.prize());
    }

    @Test
    void caso4_clave_desconocida_retorna_not_found() {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0000000000", null, null);
        TicketQueryResult result = agent.process(key, "test-004");
        assertEquals(TicketLookupStatus.NOT_FOUND, result.result());
    }

    @Test
    void caso5_modelo_sin_function_call_falla() {
        TicketModelClient sinLlamada = (key, traceId) -> Map.of();
        TicketAgent agent = new TicketAgent(sinLlamada, new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", null, null);
        assertThrows(TicketAgentException.class, () -> agent.process(key, "test-005"));
    }

    @Test
    void caso6_modelo_altera_argumentos_falla() {
        TicketModelClient alterado = (key, traceId) -> {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("lotteryCode", "OTRA");
            args.put("drawNumber", key.drawNumber());
            args.put("ticketNumber", key.ticketNumber());
            return args;
        };
        TicketAgent agent = new TicketAgent(alterado, new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", null, null);
        TicketAgentException ex = assertThrows(TicketAgentException.class, () -> agent.process(key, "test-006"));
        assertTrue(ex.getMessage().contains("lotteryCode"));
    }

    @Test
    void caso7_timeout_del_ws_se_propaga() {
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), new FixtureTicketQueryTool());
        TicketKey key = new TicketKey("LNB", "2026-10-15", "TIMEOUT", null, null);
        assertThrows(TicketTimeoutException.class, () -> agent.process(key, "test-007"));
    }

    @Test
    void caso8_ws_inconsistente_falla() {
        TicketQueryTool inconsistente = (key, traceId) -> new TicketToolResponse(
                true, false, key.ticketNumber(),
                new PrizeInfo("999.00", "USD", "CASH"), null, "SYNTHETIC");
        TicketAgent agent = new TicketAgent(new DeterministicTicketModelClient(), inconsistente);
        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", null, null);
        assertThrows(InvalidWsResponseException.class, () -> agent.process(key, "test-008"));
    }
}
