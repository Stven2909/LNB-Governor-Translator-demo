package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.SyntheticPayload;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * PAYLOAD_HASH canónico (SHA-256) del payload de negocio: columna de idempotencia de
 * WORKER_OPERATION_STATE y SYNTHETIC_PAYMENTS, y base de la recuperación in-doubt de LNB.
 * La cadena canon se genera sin espacios y con moneda a 2 decimales; cambiar el formato
 * invalida todos los hashes esperados del contrato.
 */
@Component
public class PayloadHasher {

    static final String CONTRACT_VERSION = "CONTRACT_SYNTHETIC_V0";
    static final String EVENT_TYPE = "SYNTHETIC_PAYMENT_REQUESTED";
    static final String OPERATION = "INSERT";
    static final String ENTITY = "synthetic_payment";

    public String hash(SyntheticPayload payload) {
        String canon = "{\"amount\":\"" + money(payload.amount())
                + "\",\"beneficiary\":\"" + payload.beneficiary()
                + "\",\"claimId\":\"" + payload.claimId()
                + "\",\"operationDate\":\"" + payload.operationDate() + "\"}";
        return sha256Hex(canon);
    }

    String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}