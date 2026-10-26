package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.contract.ticket.TicketQueryRequest;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
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
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        TicketQueryResult result = ticketAgent.process(request.ticket(), requestId);
        return ResponseEntity.ok(result);
    }
}
