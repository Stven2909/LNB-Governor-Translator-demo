package com.pagaduriasintetica.worker.contract;

import java.util.List;

// Fila de SYNTHETIC_PAYMENTS (resultado del commit JDBC, hoy mock en memoria): la evidencia
// del pago que sirve también para la recuperación in-doubt por PAYLOAD_HASH.
public record PaymentRow(
        String operationId,
        String payloadHash,
        String targetTable,
        List<Object> parameters
) {
}