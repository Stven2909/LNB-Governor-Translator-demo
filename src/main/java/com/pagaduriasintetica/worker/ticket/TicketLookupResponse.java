package com.pagaduriasintetica.worker.ticket;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketLookupResponse(
        boolean found,
        boolean awarded,
        String ticketNumber,
        PrizeInfo prize,
        String collectionStatus,
        String provenance,
        String databaseSource,
        String message
) {
    public static TicketLookupResponse winner(String ticketNumber, String amount, String currency, String category, String provenance, String source) {
        return new TicketLookupResponse(
                true,
                true,
                ticketNumber,
                new PrizeInfo(amount, currency, category),
                "PENDING",
                provenance,
                source,
                "Boleto encontrado y premiado"
        );
    }

    public static TicketLookupResponse notAwarded(String ticketNumber, String provenance, String source) {
        return new TicketLookupResponse(
                true,
                false,
                ticketNumber,
                null,
                null,
                provenance,
                source,
                "Boleto sin premio asignado"
        );
    }

    public static TicketLookupResponse notFound(String ticketNumber, String provenance, String source) {
        return new TicketLookupResponse(
                false,
                false,
                ticketNumber,
                null,
                null,
                provenance,
                source,
                "Boleto no encontrado en los registros de lotería"
        );
    }
}
