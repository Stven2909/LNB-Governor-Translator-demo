package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.stereotype.Service;

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
        return Map.of(
                "poolActive", repository.isPoolInitialized(),
                "lastCheck", repository.getLastCheck().toString(),
                "lastError", repository.getLastError() != null ? repository.getLastError() : "Ninguno (Conexion disponible)"
        );
    }
}
