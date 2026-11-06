package com.pagaduriasintetica.worker.agent.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;

/**
 * Respuesta del WS de tickets segun el contrato canonico (E1 / E3.2).
 *
 * <p>Tolera campos desconocidos a proposito: el contrato puede crecer (por ejemplo
 * {@code databaseSource} o {@code message}) sin romper el agente. Los campos que
 * participan en la decision se declaran aqui; el resto se ignora.</p>
 *
 * <p>{@code source} discrimina el origen del dato ({@code SYBASE} o {@code SEED}) y se
 * valida junto con {@code provenance} en {@code TicketAgent}: REAL exige SYBASE y
 * SYBASE exige REAL. Un WS antiguo que no lo envie sigue funcionando mientras no
 * afirme procedencia REAL.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TicketToolResponse(
    boolean found,
    boolean awarded,
    String ticketNumber,
    PrizeInfo prize,
    String collectionStatus,
    String provenance,
    String source,
    String databaseSource,
    String message
) {
    /** Compatibilidad con fixtures y payloads que aun no declaran {@code source}. */
    public TicketToolResponse(
            boolean found,
            boolean awarded,
            String ticketNumber,
            PrizeInfo prize,
            String collectionStatus,
            String provenance) {
        this(found, awarded, ticketNumber, prize, collectionStatus, provenance, null, null, null);
    }
}
