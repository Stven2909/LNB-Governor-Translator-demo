package com.pagaduriasintetica.worker.contract.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TicketKey(
    @NotBlank(message = "lotteryCode es obligatorio")
    @Size(max = 10, message = "lotteryCode demasiado largo")
    String lotteryCode,

    @NotBlank(message = "drawNumber es obligatorio")
    @Size(max = 20, message = "drawNumber demasiado largo")
    String drawNumber,

    @NotBlank(message = "ticketNumber es obligatorio")
    @Size(max = 32, message = "ticketNumber demasiado largo")
    String ticketNumber,

    String series,
    String checkDigit
) {}
