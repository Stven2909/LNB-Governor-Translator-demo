package com.pagaduriasintetica.worker.contract;

import java.math.BigDecimal;
import java.util.List;

// operationData del evento PAYMENT_COMMITTED (8 campos del contrato de Alex + instrumentIds
// opcional de V1). LOS MONTOS SON BigDecimal: el PAYLOAD_HASH canónico los normaliza a 2
// decimales y la invariante grossAmount - withholdingAmount == netAmount se valida con
// compareTo (ver validationRules).
// instrumentIds es NULLABLE y opcional hasta el corte V1 (decisión JD 14/10 pendiente del
// schema del productor): el productor emitirá los instrument_id en orden determinístico.
// El hash canónico aún NO lo incluye (fórmula byte-exacta pendiente del V1 de Alex).
public record OperationData(
        String paymentId,
        String claimId,
        String status,
        String paymentMethod,
        BigDecimal grossAmount,
        BigDecimal withholdingAmount,
        BigDecimal netAmount,
        String currency,
        List<String> instrumentIds
) {
}