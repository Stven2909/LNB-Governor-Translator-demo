package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;

public interface TicketQueryTool {
    TicketToolResponse query(TicketKey key, String requestId);
}
