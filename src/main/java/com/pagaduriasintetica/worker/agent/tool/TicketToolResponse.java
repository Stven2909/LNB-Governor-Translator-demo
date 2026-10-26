package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.contract.ticket.PrizeInfo;

public record TicketToolResponse(
    boolean found,
    boolean awarded,
    String ticketNumber,
    PrizeInfo prize,
    String collectionStatus,
    String provenance
) {}
