package com.pagaduriasintetica.worker.contract.ticket;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Resultado de la consulta de boleto.
 * El modelo NUNCA inventa premio/monto - solo transmite datos del WS.
 *
 * <p>{@code source} se propaga desde el WS ({@code SEED}/{@code SYBASE}) para que el
 * llamador pueda distinguir el origen sin inferirlo. En modo FIXTURE queda nulo y,
 * gracias a {@link JsonInclude.Include#NON_NULL}, no se serializa. Lo mismo aplica a
 * {@code prize}/{@code collectionStatus} en NOT_AWARDED/NOT_FOUND: el WS los omite y
 * el agente debe conservar ese shape en lugar de emitir {@code null}.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TicketQueryResult(
    String traceId,
    TicketKey ticket,
    TicketLookupStatus result,
    PrizeInfo prize,
    String collectionStatus,
    TicketProvenance provenance,
    String source,
    String message
) {}
