package com.pagaduriasintetica.worker.contract;

import java.math.BigDecimal;

// operationData del evento PAYMENT_COMMITTED (8 campos del contrato de Alex). LOS MONTOS SON
// BigDecimal: el PAYLOAD_HASH canónico los normaliza a 2 decimales y la invariante
// grossAmount - withholdingAmount == netAmount se valida con compareTo (ver validationRules).
public record OperationData(
        String paymentId,
        String claimId,
        String status,
        String paymentMethod,
        BigDecimal grossAmount,
        BigDecimal withholdingAmount,
        BigDecimal netAmount,
        String currency
) {
}