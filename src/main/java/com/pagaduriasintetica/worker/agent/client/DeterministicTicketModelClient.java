package com.pagaduriasintetica.worker.agent.client;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.model-client", havingValue = "FAKE", matchIfMissing = true)
public class DeterministicTicketModelClient implements TicketModelClient {
    @Override
    public Map<String, Object> requestToolCall(TicketKey key, String traceId) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("lotteryCode", key.lotteryCode());
        args.put("drawNumber", key.drawNumber());
        args.put("ticketNumber", key.ticketNumber());
        if (key.series() != null) args.put("series", key.series());
        if (key.checkDigit() != null) args.put("checkDigit", key.checkDigit());
        return args;
    }
}
