package com.pagaduriasintetica.worker.agent.client;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import java.util.Map;

public interface TicketModelClient {
    Map<String, Object> requestToolCall(TicketKey key, String traceId);
}
