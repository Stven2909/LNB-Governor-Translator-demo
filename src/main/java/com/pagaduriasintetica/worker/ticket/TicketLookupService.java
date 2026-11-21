package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class TicketLookupService {

    private final SybaseTicketRepository repository;
    private final AppsScriptTicketClient appsScriptClient;
    private final AppsScriptTicketProperties properties;

    public TicketLookupService(SybaseTicketRepository repository) {
        this(repository, null, new AppsScriptTicketProperties(null));
    }

    @Autowired
    public TicketLookupService(
            SybaseTicketRepository repository,
            @Autowired(required = false) AppsScriptTicketClient appsScriptClient,
            @Autowired(required = false) AppsScriptTicketProperties properties) {
        this.repository = repository;
        this.appsScriptClient = appsScriptClient;
        this.properties = properties != null ? properties : new AppsScriptTicketProperties(null);
    }

    public TicketLookupResponse lookup(TicketKey key) {
        return lookup(key, null);
    }

    public TicketLookupResponse lookup(TicketKey key, String correlationId) {
        if (key == null) {
            throw new IllegalArgumentException("TicketKey no puede ser nula");
        }

        TicketSource source = properties.resolveEffectiveSource();
        if (source == TicketSource.APPS_SCRIPT) {
            if (appsScriptClient == null) {
                throw new IllegalStateException("AppsScriptTicketClient no configurado");
            }
            return appsScriptClient.findTicket(key, correlationId);
        }

        return repository.findTicket(key.lotteryCode(), key.drawNumber(), key.ticketNumber());
    }

    public Map<String, Object> getPoolStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        TicketSource source = properties.resolveEffectiveSource();
        status.put("ticketSource", source.name());

        if (source == TicketSource.APPS_SCRIPT) {
            status.put("appsScriptUrl", properties.getAppsScript().getUrl());
            status.put("databaseSource", AppsScriptTicketClient.DATABASE_SOURCE_LABEL);
        } else {
            status.put("databaseSource", repository.getDatabaseSource());
        }

        status.put("poolActive", repository.isPoolInitialized());
        status.put("seedEnabled", repository.isSeedEnabled());
        status.put("lastError", repository.getPoolError());
        status.put("lastQueryError", repository.getLastQueryError());
        status.put("lastCheck", repository.getLastCheck() != null ? repository.getLastCheck().toString() : null);
        return status;
    }
}
