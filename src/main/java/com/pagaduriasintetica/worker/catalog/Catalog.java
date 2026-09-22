package com.pagaduriasintetica.worker.catalog;

import com.pagaduriasintetica.worker.contract.GovernorContract;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Catálogo del contrato PAYMENT_COMMITTED confirmado por Alex (catálogo V0.1 del plan
 * congelado): agrega prizes.payment -> tabla SYNTHETIC_PAYMENTS y field_mapping. Triple
 * función: pre-filtro del Worker (REJECTED antes de reservar y antes de llamar a Vertex),
 * validación exacta del output del Gobernador contra la whitelist (DLQ si alucina) y única
 * fuente de tabla/columnas para el Traductor. Nunca se escribe texto libre a Sybase.
 */
@Component
public class Catalog {

    public static final String CATALOG_JSON = "catalog/CATALOG_PAYMENT_COMMITTED_V0.1.json";
    public static final String CATALOG_VERSION = "CATALOG_PAYMENT_COMMITTED_V0.1";

    private final JsonNode root;

    public Catalog(ObjectMapper mapper) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CATALOG_JSON)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + CATALOG_JSON);
            }
            this.root = mapper.readTree(in);
        }
    }

    public boolean allows(String aggregateType, String eventType) {
        JsonNode e = root.path("aggregates").path(aggregateType);
        if (e.isMissingNode()) {
            return false;
        }
        for (JsonNode ev : e.path("eventTypes")) {
            if (ev.asString().equals(eventType)) {
                return true;
            }
        }
        return false;
    }

    public JsonNode entry(String aggregateType) {
        JsonNode e = root.path("aggregates").path(aggregateType);
        return e.isMissingNode() ? null : e;
    }

    public String targetTable(String aggregateType) {
        JsonNode e = entry(aggregateType);
        return e == null ? null : e.path("target_table").asString();
    }

    public LinkedHashMap<String, String> fieldMapping(String aggregateType) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        JsonNode e = entry(aggregateType);
        if (e == null) {
            return map;
        }
        JsonNode fm = e.path("field_mapping");
        for (String key : fm.propertyNames()) {
            map.put(key, fm.get(key).asString());
        }
        return map;
    }

    public List<String> requiredFields(String aggregateType) {
        List<String> fields = new ArrayList<>();
        JsonNode e = entry(aggregateType);
        if (e == null) {
            return fields;
        }
        e.path("required_fields").forEach(n -> fields.add(n.asString()));
        return fields;
    }

    public LinkedHashMap<String, String> valueRules(String aggregateType) {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        JsonNode e = entry(aggregateType);
        if (e == null) {
            return rules;
        }
        JsonNode v = e.path("value_rules");
        for (String key : v.propertyNames()) {
            rules.put(key, v.get(key).asString());
        }
        return rules;
    }

    public LinkedHashMap<String, String> validationRules(String aggregateType) {
        LinkedHashMap<String, String> rules = new LinkedHashMap<>();
        JsonNode e = entry(aggregateType);
        if (e == null) {
            return rules;
        }
        JsonNode v = e.path("validationRules");
        for (String key : v.propertyNames()) {
            rules.put(key, v.get(key).asString());
        }
        return rules;
    }

    public String validateGovernor(String aggregateType, GovernorContract g) {
        // Rechaza cualquier respuesta APPROVED que no coincida exactamente con el catálogo (DLQ si alucina).
        JsonNode e = entry(aggregateType);
        if (e == null) {
            return "aggregateType/eventType not in catalog";
        }
        if (g == null || g.decision() == null) {
            return "governor response missing decision";
        }
        JsonNode expectedTarget = e.path("target_table");
        if (!expectedTarget.asString().equals(g.target_table())) {
            return "target_table mismatch: expected " + expectedTarget.asString() + " got " + g.target_table();
        }
        if (g.field_mapping() == null) {
            return "field_mapping missing";
        }
        JsonNode fm = e.path("field_mapping");
        if (fm.size() != g.field_mapping().size()) {
            return "field_mapping size mismatch";
        }
        for (String key : fm.propertyNames()) {
            String expectedCol = fm.get(key).asString();
            if (!expectedCol.equals(g.field_mapping().get(key))) {
                return "field_mapping mismatch for " + key + ": expected " + expectedCol;
            }
        }
        JsonNode expectedRules = e.path("value_rules");
        if (expectedRules.size() != g.value_rules().size()) {
            return "value_rules size mismatch: expected " + expectedRules.size() + " got " + g.value_rules().size();
        }
        for (String key : expectedRules.propertyNames()) {
            String expectedValue = expectedRules.get(key).asString();
            if (!expectedValue.equals(g.value_rules().get(key))) {
                return "value_rules mismatch for " + key + ": expected " + expectedValue;
            }
        }
        if (!CATALOG_VERSION.equals(g.catalog_version())) {
            return "catalog_version mismatch";
        }
        return null;
    }
}