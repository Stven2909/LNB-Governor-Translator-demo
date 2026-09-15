package com.pagaduriasintetica.worker.contract;

import java.math.BigDecimal;

// Payload de negocio del evento (claimId, monto, fecha, beneficiario). amount es decimal y
// se normaliza a 2 decimales porque de él depende el PAYLOAD_HASH canónico.
public record SyntheticPayload(
        String claimId,
        BigDecimal amount,
        String operationDate,
        String beneficiary
) {
}