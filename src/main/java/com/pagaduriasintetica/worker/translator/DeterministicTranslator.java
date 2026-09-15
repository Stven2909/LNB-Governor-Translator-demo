package com.pagaduriasintetica.worker.translator;

import com.pagaduriasintetica.worker.catalog.Catalog;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import com.pagaduriasintetica.worker.contract.SyntheticPayload;
import com.pagaduriasintetica.worker.contract.TranslatorResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Traductor: en LNB convierte el evento + decisión del Gobernador en el SQL que se ejecuta
 * contra Sybase. Determinista y limitado al catálogo: solo columnas del field_mapping más las
 * 4 técnicas (OPERATION_ID, TRACE_ID, EVENT_ID, PAYLOAD_HASH), siempre INSERT con 8
 * parámetros (prepared statement). Cualquier columna libre = TRANSLATION_ERROR.
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
    public TranslatorResult translate(SyntheticEvent event, GovernorContract governor, String payloadHash) {
        Map<String, String> mapping = catalog.fieldMapping(event.entity());
        String target = governor.target_table();

        List<String> columns = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        for (Map.Entry<String, String> e : mapping.entrySet()) {
            columns.add(e.getValue());
            params.add(businessValue(event.payload(), e.getKey()));
        }
        columns.addAll(TECHNICAL_COLUMNS);
        params.add(event.operationId());
        params.add(event.traceId());
        params.add(event.eventId());
        params.add(payloadHash);

        String sql = "INSERT INTO " + target + " (" + String.join(", ", columns) + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        String preview = preview(target, columns, params);

        return new TranslatorResult("TRANSLATED", sql, params, preview);
    }

    private Object businessValue(SyntheticPayload payload, String field) {
        return switch (field) {
            case "claimId" -> payload.claimId();
            case "amount" -> payload.amount();
            case "operationDate" -> payload.operationDate();
            case "beneficiary" -> payload.beneficiary();
            default -> throw new IllegalStateException("field_mapping key not supported: " + field);
        };
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