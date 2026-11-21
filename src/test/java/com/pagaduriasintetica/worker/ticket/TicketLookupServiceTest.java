package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketLookupServiceTest {

    @Test
    @DisplayName("Routing APPS_SCRIPT: delega a AppsScriptTicketClient con TicketKey y correlationId")
    void routing_appsScriptDelegaAClienteConCorrelacion() {
        SybaseTicketRepository repo = Mockito.mock(SybaseTicketRepository.class);
        AppsScriptTicketClient client = Mockito.mock(AppsScriptTicketClient.class);
        AppsScriptTicketProperties props = new AppsScriptTicketProperties(null);
        props.setSource("APPS_SCRIPT");
        props.getAppsScript().setUrl("https://script.google.com/test");

        TicketLookupService service = new TicketLookupService(repo, client, props);

        TicketKey key = new TicketKey("LOTRA", "QA26-1102", "9911022000001", null, null);
        TicketLookupResponse expected = TicketLookupResponse.winner(
                "9911022000001", "100.00", "USD", "CASH", "UNPAID", "SYNTHETIC", "APPS_SCRIPT", "Google Apps Script LNB DEV"
        );
        when(client.findTicket(key, "corr-test-123")).thenReturn(expected);

        TicketLookupResponse actual = service.lookup(key, "corr-test-123");

        assertEquals(expected, actual);
        verify(client, times(1)).findTicket(key, "corr-test-123");
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("Routing SEED: delega a SybaseTicketRepository ignorando AppsScriptTicketClient")
    void routing_seedDelegaARepository() {
        SybaseTicketRepository repo = Mockito.mock(SybaseTicketRepository.class);
        AppsScriptTicketClient client = Mockito.mock(AppsScriptTicketClient.class);
        AppsScriptTicketProperties props = new AppsScriptTicketProperties(null);
        props.setSource("SEED");

        TicketLookupService service = new TicketLookupService(repo, client, props);

        TicketKey key = new TicketKey("LNB", "2026-10-15", "0012345678", null, null);
        TicketLookupResponse expected = TicketLookupResponse.winner(
                "0012345678", "50.00", "USD", "CASH", "PENDING", "SYNTHETIC", "SEED", "Fixture autorizado LNB DEV"
        );
        when(repo.findTicket("LNB", "2026-10-15", "0012345678")).thenReturn(expected);

        TicketLookupResponse actual = service.lookup(key, "corr-test-seed");

        assertEquals(expected, actual);
        verify(repo, times(1)).findTicket("LNB", "2026-10-15", "0012345678");
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("Status APPS_SCRIPT: reporta ticketSource=APPS_SCRIPT y url configurada")
    void status_reportaDatosDeAppsScript() {
        SybaseTicketRepository repo = Mockito.mock(SybaseTicketRepository.class);
        AppsScriptTicketProperties props = new AppsScriptTicketProperties(null);
        props.setSource("APPS_SCRIPT");
        props.getAppsScript().setUrl("https://script.google.com/test-url");

        TicketLookupService service = new TicketLookupService(repo, null, props);

        Map<String, Object> status = service.getPoolStatus();
        assertEquals("APPS_SCRIPT", status.get("ticketSource"));
        assertEquals("https://script.google.com/test-url", status.get("appsScriptUrl"));
        assertEquals(AppsScriptTicketClient.DATABASE_SOURCE_LABEL, status.get("databaseSource"));
    }

    @Test
    @DisplayName("Validación fail-closed: TicketKey nula lanza IllegalArgumentException")
    void validacion_ticketKeyNulaLanzaExcepcion() {
        SybaseTicketRepository repo = Mockito.mock(SybaseTicketRepository.class);
        TicketLookupService service = new TicketLookupService(repo);

        assertThrows(IllegalArgumentException.class, () -> service.lookup(null));
    }
}
