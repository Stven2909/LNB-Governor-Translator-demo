package com.pagaduriasintetica.worker.contract.ticket;

/**
 * Resultado de la consulta de boleto.
 * El modelo NUNCA inventa premio/monto - solo transmite datos del WS.
 */
public record TicketQueryResult(
    String traceId,
    TicketKey ticket,
    TicketLookupStatus result,
    PrizeInfo prize,
    String collectionStatus,
    TicketProvenance provenance,
    String message
) {}