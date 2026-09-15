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
import java.util.Map;

/**
 * Catálogo sintético V0 (whitelist de LNB): entidad synthetic_payment -> operación INSERT ->
 * tabla SYNTHETIC_PAYMENTS y field_mapping. Triple función: pre-filtro del Worker (REJECTED
 * antes de llamar a Vertex), validación exacta del output del Gobernador (DLQ si alucina) y
 * única fuente de tabla/columnas para el Traductor. Nunca se escribe texto libre a Sybase.
 */
@Component
public class Catalog {

    public static final String CATALOG_JSON = "catalog/CATALOG_SYNTHETIC_V0.json";
    public static final String CATALOG_VERSION = "CATALOG_SYNTHETIC_V0";

    private final JsonNode root;

    public Catalog(ObjectMapper mapper) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CATALOG_JSON)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + CATALOG_JSON);
            }
            this.root = mapper.readTree(in);
        }
    }

    public boolean allows(String entity, String operation, String eventType) {
        JsonNode e = root.path("entities").path(entity);
        if (e.isMissingNode()) {
            return false;
        }
        for (JsonNode op : e.path("operations")) {
            if (op.asString().equals(operation)) {
                for (JsonNode ev : e.path("eventTypes")) {
                    if (ev.asString().equals(eventType)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public JsonNode entry(String entity) {
        JsonNode e = root.path("entities").path(entity);
        return e.isMissingNode() ? null : e;
    }

    public String targetTable(String entity) {
        JsonNode e = entry(entity);
        return e == null ? null : e.path("target_table").asString();
    }

    public LinkedHashMap<String, String> fieldMapping(String entity) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        JsonNode e = entry(entity);
        if (e == null) {
            return map;
        }
        JsonNode fm = e.path("field_mapping");
        for (String key : fm.propertyNames()) {
            map.put(key, fm.get(key).asString());
        }
        return map;
    }

    public List<String> requiredFields(String entity) {
        List<String> fields = new ArrayList<>();
        JsonNode e = entry(entity);
        if (e == null) {
            return fields;
        }
        e.path("required_fields").forEach(n -> fields.add(n.asString()));
        return fields;
    }

    public JsonNode valueRules(String entity) {
        JsonNode e = entry(entity);
        return e == null ? null : e.path("value_rules");
    }

    public String validateGovernor(String entity, GovernorContract g) {
        // Rechaza cualquier respuesta APPROVED que no coincida exactamente con el catálogo (Caso 4 -> DLQ).
        JsonNode e = entry(entity);
        if (e == null) {
            return "entity not in catalog";
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
        if (g.value_rules() != null && !g.value_rules().isEmpty()) {
            return "value_rules not allowed for this catalog";
        }
        if (!CATALOG_VERSION.equals(g.catalog_version())) {
            return "catalog_version mismatch";
        }
        return null;
    }
}