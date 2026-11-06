package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.client.TicketModelClient;
import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.tool.TicketQueryTool;
import com.pagaduriasintetica.worker.agent.tool.TicketToolResponse;
import com.pagaduriasintetica.worker.contract.ticket.*;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Component
public class TicketAgent {

    private final TicketModelClient modelClient;
    private final TicketQueryTool ticketQueryTool;

    public TicketAgent(TicketModelClient modelClient, TicketQueryTool ticketQueryTool) {
        this.modelClient = modelClient;
        this.ticketQueryTool = ticketQueryTool;
    }

    public TicketQueryResult process(TicketKey key, String requestId) {
        String traceId = StringUtils.hasText(requestId) ? requestId.trim() : UUID.randomUUID().toString();

        Map<String, Object> args = modelClient.requestToolCall(key, traceId);
        verifyArgs(key, args);

        TicketToolResponse ws = ticketQueryTool.query(key, traceId);
        return assemble(ws, key, traceId);
    }

    private void verifyArgs(TicketKey expected, Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            throw new TicketAgentException("FunctionCall sin argumentos");
        }
        requireEqual(args, "lotteryCode", expected.lotteryCode());
        requireEqual(args, "drawNumber", expected.drawNumber());
        requireEqual(args, "ticketNumber", expected.ticketNumber());
    }

    private void requireEqual(Map<String, Object> args, String name, String expected) {
        Object v = args.get(name);
        if (v == null) {
            throw new TicketAgentException(name + " faltante en functionCall");
        }
        if (!expected.equals(String.valueOf(v))) {
            throw new TicketAgentException(name + " alterado por el modelo");
        }
    }

    private TicketQueryResult assemble(TicketToolResponse ws, TicketKey ticket, String traceId) {
        if (ws == null) {
            throw new InvalidWsResponseException("Respuesta WS nula");
        }
        if (!StringUtils.hasText(ws.ticketNumber())) {
            throw new InvalidWsResponseException("ticketNumber ausente en respuesta WS");
        }
        if (!ticket.ticketNumber().equals(ws.ticketNumber())) {
            throw new InvalidWsResponseException("ticketNumber no coincide con la clave solicitada");
        }

        TicketProvenance provenance = resolveProvenance(ws.provenance(), ws.source());

        if (!ws.found()) {
            if (ws.awarded() || ws.prize() != null) {
                throw new InvalidWsResponseException("NOT_FOUND inconsistente con awarded/prize");
            }
            return new TicketQueryResult(traceId, ticket, TicketLookupStatus.NOT_FOUND, null, ws.collectionStatus(), provenance, "Boleto no encontrado");
        }

        if (!ws.awarded()) {
            if (ws.prize() != null) {
                throw new InvalidWsResponseException("NOT_AWARDED inconsistente con prize");
            }
            return new TicketQueryResult(traceId, ticket, TicketLookupStatus.NOT_AWARDED, null, ws.collectionStatus(), provenance, "Boleto no premiado");
        }

        if (ws.prize() == null) {
            throw new InvalidWsResponseException("FOUND requiere prize");
        }
        return new TicketQueryResult(traceId, ticket, TicketLookupStatus.FOUND, ws.prize(), ws.collectionStatus(), provenance, "Boleto encontrado y premiado");
    }

    /**
     * Procedencia declarada por el WS, con la regla de oro del contrato E1:
     * un dato REAL solo puede venir de Sybase. Si el WS dice REAL sin declarar
     * {@code source: SYBASE}, o declara SYBASE sin decir REAL, se falla cerrado:
     * nunca se completa la procedencia por conveniencia del adaptador.
     */
    private TicketProvenance resolveProvenance(String provenanceValue, String sourceValue) {
        if (!StringUtils.hasText(provenanceValue)) {
            throw new InvalidWsResponseException("provenance es obligatorio");
        }

        TicketProvenance provenance;
        try {
            provenance = TicketProvenance.valueOf(provenanceValue.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new InvalidWsResponseException("provenance inválida: " + provenanceValue);
        }

        String source = StringUtils.hasText(sourceValue) ? sourceValue.trim().toUpperCase(Locale.ROOT) : null;
        if (source != null && !"SYBASE".equals(source) && !"SEED".equals(source)) {
            throw new InvalidWsResponseException("source inválida: " + sourceValue);
        }
        if (provenance == TicketProvenance.REAL && !"SYBASE".equals(source)) {
            throw new InvalidWsResponseException("provenance REAL exige source SYBASE");
        }
        if ("SYBASE".equals(source) && provenance != TicketProvenance.REAL) {
            throw new InvalidWsResponseException("source SYBASE exige provenance REAL");
        }
        if ("SEED".equals(source) && provenance != TicketProvenance.SYNTHETIC) {
            throw new InvalidWsResponseException("source SEED exige provenance SYNTHETIC");
        }
        return provenance;
    }
}
