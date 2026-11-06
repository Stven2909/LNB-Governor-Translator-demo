package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TicketLookupService {

    private final SybaseTicketRepository repository;

    public TicketLookupService(SybaseTicketRepository repository) {
        this.repository = repository;
    }

    public TicketLookupResponse lookup(TicketKey key) {
        if (key == null) {
            throw new IllegalArgumentException("TicketKey no puede ser nula");
        }
        return repository.findTicket(key.lotteryCode(), key.drawNumber(), key.ticketNumber());
    }

    public Map<String, Object> getPoolStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("poolActive", repository.isPoolInitialized());
        status.put("seedEnabled", repository.isSeedEnabled());
        status.put("lastError", repository.getPoolError());
        status.put("lastQueryError", repository.getLastQueryError());
        status.put("lastCheck", repository.getLastCheck() != null ? repository.getLastCheck().toString() : null);
        return status;
    }
}
