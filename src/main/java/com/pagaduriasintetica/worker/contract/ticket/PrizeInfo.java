package com.pagaduriasintetica.worker.contract.ticket;

/**
 * Información del premio.
 * Solo existe cuando result = FOUND.
 */
public record PrizeInfo(
    String amount,
    String currency,
    String category
) {}