package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.contract.ticket.TicketQueryRequest;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Endpoint del agente de boleto (E3.2).
 *
 * <p>Se excluye del perfil {@code carlos} porque ese perfil representa el rol del WS: el
 * servicio que expone el contrato de boletos no debe exponer tambien la ruta del agente. Sin
 * esta exclusion, un error de URL en la demostracion devuelve un {@code FOUND} de fixture por
 * {@code /api/v1/agent/ticket-query} en el servicio del WS, que es exactamente el dato falso que
 * se quiere evitar. Con la exclusion, esa ruta responde 404 en el servicio equivocado.</p>
 */
@RestController
@RequestMapping("/api/v1/agent")
@Profile("!carlos")
public class TicketAgentController {

    private final TicketAgent ticketAgent;

    public TicketAgentController(TicketAgent ticketAgent) {
        this.ticketAgent = ticketAgent;
    }

    @PostMapping("/ticket-query")
    public ResponseEntity<TicketQueryResult> queryTicket(
            @Valid @RequestBody TicketQueryRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        // Se acepta X-Correlation-Id (convencion del ecosistema) y X-Request-Id (contrato previo).
        // El filtro ya lo fijo en atributo y cabeceras; se repite aqui para que la respuesta
        // conserve el eco incluso si el controlador se monta sin el filtro (pruebas standalone).
        String correlationId = CorrelationId.currentOrGenerate(httpRequest);
        httpResponse.setHeader(CorrelationId.HEADER, correlationId);
        httpResponse.setHeader(CorrelationId.LEGACY_HEADER, correlationId);
        TicketQueryResult result = ticketAgent.process(request.ticket(), correlationId);
        return ResponseEntity.ok(result);
    }
}
