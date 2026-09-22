package com.pagaduriasintetica.worker.translator;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.TranslatorInput;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Traductor: en LNB convierte TranslatorInput (evento + GovernorContract ya validado por el
 * catálogo) en el PreparedStatement con 12 parámetros (8 de negocio del field_mapping + 4
 * técnicas: OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH). Determinista: solo columnas de la
 * whitelist; aplica value_rules autorizados (p. ej. COMMITTED -> codigo legacy aprobado), no
 * recalcula montos. Cualquier columna libre = TRANSLATION_ERROR.
 */
@Component
public class DeterministicTranslator implements Translator {

    public static final List<String> TECHNICAL_COLUMNS = List.of(
            "OPERATION_ID", "TRACE_ID", "EVENT_ID", "PAYLOAD_HASH"
    );

    private final Catalog catalog;

    public DeterministicTranslator(Catalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public TranslatorResult translate(TranslatorInput input) {
        OperationData operationData = input.event().operationData();
        String aggregateType = input.event().aggregateType();
        Map<String, String> mapping = catalog.fieldMapping(aggregateType);
        Map<String, String> valueRules = catalog.valueRules(aggregateType);
        String target = input.governor().target_table();

        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            columns.add(e.getValue());
            params.add(businessValue(operationData, e.getKey(), valueRules));
        }
        columns.addAll(TECHNICAL_COLUMNS);
        params.add(input.event().operationId());
        params.add(input.workerTraceId());
        params.add(input.event().eventId());
        params.add(input.payloadHash());

        String sql = "INSERT INTO " + target + " (" + String.join(", ", columns) + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        String preview = preview(target, columns, params);

        return new TranslatorResult("TRANSLATED", sql, params, preview);
    }

    private Object businessValue(OperationData data, String field, Map<String, String> valueRules) {
        return switch (field) {
            case "paymentId" -> data.paymentId();
            case "claimId" -> data.claimId();
            case "status" -> rule(valueRules, "status." + data.status(), data.status());
            case "paymentMethod" -> rule(valueRules, "paymentMethod." + data.paymentMethod(), data.paymentMethod());
            case "grossAmount" -> data.grossAmount();
            case "withholdingAmount" -> data.withholdingAmount();
            case "netAmount" -> data.netAmount();
            case "currency" -> rule(valueRules, "currency." + data.currency(), data.currency());
            default -> throw new IllegalStateException("field_mapping key not supported: " + field);
        };
    }

    private String rule(Map<String, String> valueRules, String key, String fallback) {
        String transformed = valueRules.get(key);
        return transformed == null ? fallback : transformed;
    }

    private String preview(String target, List<String> columns, List<Object> params) {
        StringBuilder sb = new StringBuilder("INSERT INTO " + target + " (");
        sb.append(String.join(", ", columns)).append(") VALUES (");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            Object value = params.get(i);
            if (value instanceof Number) {
                sb.append(value);
            } else {
                sb.append('\'').append(value).append('\'');
            }
        }
        return sb.append(')').toString();
    }
}