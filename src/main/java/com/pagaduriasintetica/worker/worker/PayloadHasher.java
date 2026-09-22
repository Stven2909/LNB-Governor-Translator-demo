package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationData;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * PAYLOAD_HASH canónico (SHA-256) del operationData del evento PAYMENT_COMMITTED: columna de
 * idempotencia de WORKER_OPERATION_STATE y SYNTHETIC_PAYMENTS, y base de la recuperación
 * in-doubt de LNB. La cadena canon se genera SIN espacios, con claves en orden alfabético y
 * montos a 2 decimales; cambiar el formato invalida todos los hashes esperados del contrato.
 */
@Component
public class PayloadHasher {

    static final String CONTRACT_VERSION = "CONTRACT_PAYMENT_COMMITTED_V0.1";
    static final String EVENT_TYPE = "PAYMENT_COMMITTED";
    static final String AGGREGATE_TYPE = "prizes.payment";
    static final int EVENT_VERSION = 1;

    public String hash(OperationData data) {
        TreeMap<String, String> canon = new TreeMap<>();
        canon.put("paymentId", data.paymentId());
        canon.put("claimId", data.claimId());
        canon.put("status", data.status());
        canon.put("paymentMethod", data.paymentMethod());
        canon.put("grossAmount", money(data.grossAmount()));
        canon.put("withholdingAmount", money(data.withholdingAmount()));
        canon.put("netAmount", money(data.netAmount()));
        canon.put("currency", data.currency());

        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (var e : canon.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append('"').append(e.getKey()).append("\":\"").append(e.getValue()).append('"');
        }
        return sha256Hex(sb.append('}').toString());
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