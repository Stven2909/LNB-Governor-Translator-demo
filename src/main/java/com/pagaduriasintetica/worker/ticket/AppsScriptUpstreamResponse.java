package com.pagaduriasintetica.worker.ticket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Modelo DTO para deserializar la respuesta del Google Apps Script upstream.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AppsScriptUpstreamResponse(
        Boolean found,
        Boolean awarded,
        String ticketNumber,
        AppsScriptPrize prize,
        String collectionStatus,
        String provenance,
        String requestId
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppsScriptPrize(
            String amount,
            String currency,
            String category
    ) {}
}
