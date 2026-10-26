package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.mode", havingValue = "FIXTURE", matchIfMissing = true)
public class FixtureTicketQueryTool implements TicketQueryTool {

    private final Map<String, TicketToolResponse> fixtures = new LinkedHashMap<>();

    public FixtureTicketQueryTool() {
        fixtures.put("LNB-2026-10-15-0012345678", new TicketToolResponse(
                true, true, "0012345678",
                new PrizeInfo("150.00", "USD", "CASH"),
                "PENDING",
                "SYNTHETIC"
        ));
        fixtures.put("LNB-2026-10-15-9999999999", new TicketToolResponse(
                false, false, "9999999999",
                null,
                null,
                "SYNTHETIC"
        ));
        fixtures.put("LNB-2026-10-15-0011111111", new TicketToolResponse(
                true, false, "0011111111",
                null,
                null,
                "SYNTHETIC"
        ));
    }

    @Override
    public TicketToolResponse query(TicketKey key, String requestId) {
        String fixtureKey = String.format("%s-%s-%s", key.lotteryCode(), key.drawNumber(), key.ticketNumber());
        if (fixtureKey.equals("LNB-2026-10-15-TIMEOUT")) {
            throw new TicketTimeoutException("Timeout del WS de tickets");
        }
        TicketToolResponse r = fixtures.get(fixtureKey);
        if (r == null) {
            return new TicketToolResponse(false, false, key.ticketNumber(), null, null, "SYNTHETIC");
        }
        return r;
    }
}
