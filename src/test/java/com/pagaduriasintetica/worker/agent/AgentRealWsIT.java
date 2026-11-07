package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.client.DeterministicTicketModelClient;
import com.pagaduriasintetica.worker.agent.tool.HttpTicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import com.pagaduriasintetica.worker.contract.ticket.TicketLookupStatus;
import com.pagaduriasintetica.worker.contract.ticket.TicketProvenance;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integracion REAL contra el WS de boletos (modo semilla o Sybase).
 *
 * <p>No se ejecuta en la suite normal: exige {@code AGENT_WS_IT=true} y
 * {@code AGENT_WS_BASE_URL} (por ejemplo {@code http://localhost:8085}). El nombre
 * termina en {@code IT} para que Surefire no la recoja por defecto; se lanza de forma
 * explicita cuando el WS esta levantado:</p>
 *
 * <pre>
 * $env:AGENT_WS_IT="true"
 * $env:AGENT_WS_BASE_URL="http://localhost:8085"
 * .\mvnw.cmd test -Dtest=AgentRealWsIT
 * </pre>
 *
 * <p>Sobre modo semilla se espera {@code provenance=SYNTHETIC} y {@code source=SEED};
 * sobre Sybase real, {@code REAL}/{@code SYBASE}. La prueba acepta ambos y verifica la
 * coherencia del gate sin inventar el origen.</p>
 */
@EnabledIfEnvironmentVariable(named = "AGENT_WS_IT", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "AGENT_WS_BASE_URL", matches = ".+")
class AgentRealWsIT {

    private static final String DRAW = "2026-10-15";

    private static TicketAgent agent() {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setMode("HTTP");
        props.setBaseUrl(System.getenv("AGENT_WS_BASE_URL"));
        props.setTimeout("15s");
        return new TicketAgent(
                new DeterministicTicketModelClient(),
                new HttpTicketQueryTool(props));
    }

    @Test
    void caso1_premiado_50_usd_cash_pending() {
        TicketQueryResult result = agent().process(
                new TicketKey("LNB", DRAW, "0012345678", null, null), "it-found");

        assertEquals(TicketLookupStatus.FOUND, result.result());
        assertNotNull(result.prize());
        assertEquals("50.00", result.prize().amount());
        assertEquals("USD", result.prize().currency());
        assertEquals("CASH", result.prize().category());
        assertEquals("PENDING", result.collectionStatus());
        assertTrue(result.provenance() == TicketProvenance.SYNTHETIC
                        || result.provenance() == TicketProvenance.REAL,
                "provenance coherente con el origen que declare el WS");
    }

    @Test
    void caso2_no_premiado() {
        TicketQueryResult result = agent().process(
                new TicketKey("LNB", DRAW, "0011111111", null, null), "it-not-awarded");

        assertEquals(TicketLookupStatus.NOT_AWARDED, result.result());
        assertNull(result.prize());
    }

    @Test
    void caso3_no_encontrado() {
        TicketQueryResult result = agent().process(
                new TicketKey("LNB", DRAW, "9999999999", null, null), "it-not-found");

        assertEquals(TicketLookupStatus.NOT_FOUND, result.result());
        assertNull(result.prize());
    }
}
