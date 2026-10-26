package com.pagaduriasintetica.worker.contract.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Wrapper de entrada para el agente de consulta de boleto.
 */
public record TicketQueryRequest(
    @NotNull(message = "ticket es obligatorio")
    @Valid
    TicketKey ticket
) {}