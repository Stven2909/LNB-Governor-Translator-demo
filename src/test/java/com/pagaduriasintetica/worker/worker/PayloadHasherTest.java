package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationData;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.HASH_OP001;
import static com.pagaduriasintetica.worker.worker.TestEnvelopeFactory.validData;
import static org.junit.jupiter.api.Assertions.assertEquals;

// Bloquea el hash canónico del fixture del contrato, la normalización de moneda a 2 decimales
// y la equivalencia decimal 1.10 == 1.1 de la canonicalización (plan Fase 5, prueba 8).
class PayloadHasherTest {

    private final PayloadHasher hasher = new PayloadHasher();

    @Test
    void canonicalHashMatchesContractExample() {
        assertEquals(HASH_OP001, hasher.hash(validData()));
    }

    @Test
    void amountIsNormalizedToTwoDecimals() {
        OperationData data = new OperationData("pay-001", "claim-001", "COMMITTED", "CASH",
                new BigDecimal("200"), new BigDecimal("50"), new BigDecimal("150"), "USD");
        assertEquals(HASH_OP001, hasher.hash(data));
    }

    @Test
    void decimalEquivalence() {
        OperationData a = new OperationData("p", "c", "COMMITTED", "CASH",
                new BigDecimal("1.10"), BigDecimal.ZERO, new BigDecimal("1.10"), "USD");
        OperationData b = new OperationData("p", "c", "COMMITTED", "CASH",
                new BigDecimal("1.1"), BigDecimal.ZERO, new BigDecimal("1.1"), "USD");
        assertEquals(hasher.hash(a), hasher.hash(b));
    }
}