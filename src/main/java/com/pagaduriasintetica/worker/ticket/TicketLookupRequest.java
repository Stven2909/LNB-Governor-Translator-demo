package com.pagaduriasintetica.worker.ticket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketLookupRequest {

    private String lotteryCode;
    private String drawNumber;
    private String ticketNumber;
    private String series;
    private String checkDigit;

    public TicketLookupRequest() {}

    public TicketLookupRequest(String lotteryCode, String drawNumber, String ticketNumber, String series, String checkDigit) {
        this.lotteryCode = lotteryCode;
        this.drawNumber = drawNumber;
        this.ticketNumber = ticketNumber;
        this.series = series;
        this.checkDigit = checkDigit;
    }

    /** Permite aceptar tanto el objeto plano como envuelto en {"ticket": { ... }} */
    @JsonProperty("ticket")
    public void unpackTicket(TicketKey nested) {
        if (nested != null) {
            this.lotteryCode = nested.lotteryCode();
            this.drawNumber = nested.drawNumber();
            this.ticketNumber = nested.ticketNumber();
            this.series = nested.series();
            this.checkDigit = nested.checkDigit();
        }
    }

    public String getLotteryCode() { return lotteryCode; }
    public void setLotteryCode(String lotteryCode) { this.lotteryCode = lotteryCode; }

    public String getDrawNumber() { return drawNumber; }
    public void setDrawNumber(String drawNumber) { this.drawNumber = drawNumber; }

    public String getTicketNumber() { return ticketNumber; }
    public void setTicketNumber(String ticketNumber) { this.ticketNumber = ticketNumber; }

    public String getSeries() { return series; }
    public void setSeries(String series) { this.series = series; }

    public String getCheckDigit() { return checkDigit; }
    public void setCheckDigit(String checkDigit) { this.checkDigit = checkDigit; }

    public TicketKey toTicketKey() {
        return new TicketKey(
                lotteryCode != null ? lotteryCode.trim() : "",
                drawNumber != null ? drawNumber.trim() : "",
                ticketNumber != null ? ticketNumber.trim() : "",
                series != null ? series.trim() : null,
                checkDigit != null ? checkDigit.trim() : null
        );
    }
}
