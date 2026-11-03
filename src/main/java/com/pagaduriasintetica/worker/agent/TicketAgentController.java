package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.contract.ticket.TicketQueryRequest;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/agent")
public class TicketAgentController {

    private final TicketAgent ticketAgent;

    public TicketAgentController(TicketAgent ticketAgent) {
        this.ticketAgent = ticketAgent;
    }

    @PostMapping("/ticket-query")
    public ResponseEntity<TicketQueryResult> queryTicket(
            @Valid @RequestBody TicketQueryRequest request,
            HttpServletRequest httpRequest) {
        // Se acepta X-Correlation-Id (convencion del ecosistema) y X-Request-Id (contrato previo).
        String correlationId = CorrelationId.resolve(httpRequest);
        TicketQueryResult result = ticketAgent.process(request.ticket(), correlationId);
        return ResponseEntity.ok(result);
    }
}
