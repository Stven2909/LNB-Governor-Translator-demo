package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import com.pagaduriasintetica.worker.governor.Governor;
import com.pagaduriasintetica.worker.translator.Translator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Aprobacion valida del catalogo reutilizable por los tests que necesitan que el Governor responda
 * correctamente despues de simular un fallo. Se extrajo de BranchingCoverageTest porque la
 * clasificacion de fallos (G4) necesita alternar entre fallo y aprobacion sobre el mismo mock.
 */
final class BranchingCoverageTestSupport {

    private BranchingCoverageTestSupport() {
    }

    /** Configura elGovernor mock para que apruebe con el plan exacto del catalogo. */
    static void whenGovernorApproves(Governor governor, String operationId) {
        Map<String, String> fieldMapping = new LinkedHashMap<>();
        fieldMapping.put("paymentId", "ID_PAGO");
        fieldMapping.put("claimId", "COD_RECLAMO");
        fieldMapping.put("status", "ESTADO");
        fieldMapping.put("paymentMethod", "MEDIO_PAGO");
        fieldMapping.put("grossAmount", "MONTO_BRUTO");
        fieldMapping.put("withholdingAmount", "MONTO_RETENCION");
        fieldMapping.put("netAmount", "MONTO_NETO");
        fieldMapping.put("currency", "MONEDA");
        Map<String, Object> valueRules = new LinkedHashMap<>();
        valueRules.put("status.COMMITTED", "APROBADO");
        valueRules.put("paymentMethod.CASH", "EFECTIVO");
        valueRules.put("paymentMethod.TRANSFER", "TRANSFERENCIA");
        valueRules.put("currency.USD", "USD");
        when(governor.decide(any(GovernorInput.class))).thenReturn(new GovernorContract(
                "CONTRACT_PAYMENT_COMMITTED_V0.1", GovernorDecision.APPROVED, operationId, "wtr-1",
                "Operación validada correctamente", "SYNTHETIC_PAYMENTS",
                List.of("paymentId", "claimId", "status", "paymentMethod", "grossAmount",
                        "withholdingAmount", "netAmount", "currency"),
                fieldMapping, valueRules, Catalog.CATALOG_VERSION, List.of()));
    }

    /**
     * Plan traducido valido (12 parametros: 8 de negocio + OPERATION_ID, TRACE_ID, EVENT_ID y
     * PAYLOAD_HASH). Necesario cuando el test simula un fallo del Governor y luego quiere que la
     * redelivery complete el pipeline completo hasta JDBC.
     */
    static void whenTranslatorValid(Translator translator, String operationId) {
        String sql = "INSERT INTO SYNTHETIC_PAYMENTS (ID_PAGO, COD_RECLAMO, ESTADO, MEDIO_PAGO, "
                + "MONTO_BRUTO, MONTO_RETENCION, MONTO_NETO, MONEDA, OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        when(translator.translate(any(TranslatorInput.class))).thenReturn(new TranslatorResult("TRANSLATED",
                sql, List.of("pay-001", "claim-001", "APROBADO", "EFECTIVO",
                "200.00", "50.00", "150.00", "USD", operationId, "wtr-1", "evt-001", "hash"),
                "INSERT INTO SYNTHETIC_PAYMENTS (...)"));
    }
}