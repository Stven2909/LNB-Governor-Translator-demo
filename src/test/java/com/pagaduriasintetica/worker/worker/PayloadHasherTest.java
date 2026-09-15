package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.SyntheticPayload;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Bloquea el hash canónico del ejemplo del contrato y la normalización de moneda a 2 decimales.
class PayloadHasherTest {

    private final PayloadHasher hasher = new PayloadHasher();

    @Test
    void canonicalHashMatchesContractExample() {
        SyntheticPayload payload = new SyntheticPayload(
                "claim-001", new BigDecimal("150.00"), "2026-09-07", "Ana Gomez");
        assertEquals("8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6",
                hasher.hash(payload));
    }

    @Test
    void amountIsNormalizedToTwoDecimals() {
        SyntheticPayload payload = new SyntheticPayload(
                "claim-001", new BigDecimal("150"), "2026-09-07", "Ana Gomez");
        assertEquals("8c0f97a3091623b1c5f850a40b146fa6cd417301e4ec2caee4db9b2f9a1389d6",
                hasher.hash(payload));
    }
}