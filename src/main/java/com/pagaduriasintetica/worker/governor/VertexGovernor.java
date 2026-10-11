package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.config.WorkerProperties;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorEnrichment;
import com.pagaduriasintetica.worker.contract.GovernorEnrichmentStatus;
import com.pagaduriasintetica.worker.contract.GovernorInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gobernador real de E3.2: la DECISION y el plan vienen del API determinista (catálogo congelado,
 * via DeterministicGovernorSupport). Vertex AI solo produce ENRIQUECIMIENTO NO AUTORITATIVO que se
 * adjunta al contrato y NUNCA modifica la decision, el plan ni el SQL. No hay reglas ocultas.
 *
 * Clasificacion (vease GovernorEnrichmentStatus / GovernorEnrichment.unavailableReason):
 * - 401/403/404 -> GovernorConfigurationException -> el Worker mapea a BLOCKED_CONFIGURATION (ACK terminal).
 * - 408/429/5xx/IO/timeout -> UNAVAILABLE (ACK, decision deterministica intacta), motivo distinguible.
 * - safety block / JSON malformado / responseSchema violado -> UNAVAILABLE con
 *   ENRICHMENT_SAFETY_BLOCK / ENRICHMENT_INVALID / ENRICHMENT_SCHEMA_VIOLATION.
 */
public class VertexGovernor implements Governor {

    private static final Logger log = LoggerFactory.getLogger(VertexGovernor.class);

    static final String CODE_SCHEMA_VIOLATION = "ENRICHMENT_SCHEMA_VIOLATION";
    static final String CODE_INVALID = "ENRICHMENT_INVALID";
    static final String CODE_SAFETY_BLOCK = "ENRICHMENT_SAFETY_BLOCK";
    static final String CODE_UNEXPECTED = "VERTEX_UNAVAILABLE_UNEXPECTED";

    private final VertexClient client;
    private final DeterministicGovernorSupport support;
    private final WorkerProperties.Vertex props;
    private final ObjectMapper mapper;
    private final String promptText;

    public VertexGovernor(VertexClient client,
                          DeterministicGovernorSupport support,
                          WorkerProperties.Vertex props,
                          ObjectMapper mapper) throws IOException {
        this.client = client;
        this.support = support;
        this.props = props;
        this.mapper = mapper;
        this.promptText = loadPrompt(props.getPromptVersion());
    }

    /** Instruccion fija + contexto de la operacion: enriquecimiento, nunca decision/plan/SQL. */
    @Override
    public GovernorContract decide(GovernorInput input) {
        VertexOutcome outcome;
        try {
            outcome = client.generate(promptText, buildUserPrompt(input));
        } catch (VertexCallException e) {
            if (e.isConfigurationFailure()) {
                log.error("Vertex configuracion erronea ({} {}) para operationId={}",
                        e.code(), e.status(), input.event().operationId(), e);
                throw new GovernorConfigurationException(
                        "Vertex AI call failed " + e.code() + " " + e.status() + ": " + e.getMessage(), e);
            }
            return degrade(input, codeForTransient(e));
        } catch (Exception e) {
            log.warn("Vertex inesperado para operationId={}", input.event().operationId(), e);
            return degrade(input, CODE_UNEXPECTED);
        }

        if (outcome == null || outcome.safetyBlocked()) {
            return degrade(input, CODE_SAFETY_BLOCK);
        }
        String text = outcome.text();
        if (text == null || text.isBlank()) {
            return degrade(input, CODE_SAFETY_BLOCK);
        }
        return attach(input, text);
    }

    private GovernorContract attach(GovernorInput input, String text) {
        GovernorEnrichment enrichment;
        try {
            enrichment = parseAndValidate(text);
        } catch (SchemaViolation e) {
            return degrade(input, CODE_SCHEMA_VIOLATION);
        } catch (InvalidJson e) {
            return degrade(input, CODE_INVALID);
        }
        return support.approve(input, GovernorEnrichmentStatus.ATTACHED, enrichment);
    }

    private GovernorContract degrade(GovernorInput input, String reason) {
        log.warn("Vertex enrichment UNAVAILABLE reason={} operationId={}", reason, input.event().operationId());
        return support.approve(input, GovernorEnrichmentStatus.UNAVAILABLE,
                GovernorEnrichment.unavailable(reason));
    }

    /** JSON invalido vs JSON valido que viola el responseSchema: codigos distinguibles. */
    private GovernorEnrichment parseAndValidate(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            throw new InvalidJson(e);
        }
        if (root == null || !root.isObject()) {
            throw new SchemaViolation("root is not a JSON object");
        }
        JsonNode summary = root.get("summary");
        if (summary == null || !summary.isTextual() || summary.asText().isBlank()) {
            throw new SchemaViolation("required string field 'summary' missing or empty");
        }
        JsonNode observations = root.get("observations");
        if (observations == null || !observations.isArray()) {
            throw new SchemaViolation("required array field 'observations' missing");
        }
        List<String> obs = new ArrayList<>();
        for (JsonNode o : observations) {
            if (!o.isTextual()) {
                throw new SchemaViolation("'observations' must contain only strings");
            }
            obs.add(o.asText());
        }
        Map<String, String> suggested = new LinkedHashMap<>();
        JsonNode suggestedNode = root.get("suggested_mapping");
        if (suggestedNode != null && !suggestedNode.isNull()) {
            if (!suggestedNode.isObject()) {
                throw new SchemaViolation("'suggested_mapping' must be a string-to-string object");
            }
            for (String key : suggestedNode.propertyNames()) {
                JsonNode v = suggestedNode.get(key);
                if (!v.isTextual()) {
                    throw new SchemaViolation("'suggested_mapping' values must be strings");
                }
                suggested.put(key, v.asText());
            }
        }
        return GovernorEnrichment.attached(summary.asText(), obs, suggested);
    }

    private String codeForTransient(VertexCallException e) {
        switch (e.code()) {
            case 408:
                return "VERTEX_UNAVAILABLE_TIMEOUT_408";
            case 429:
                return "VERTEX_UNAVAILABLE_QUOTA_429";
            case -1:
                return "VERTEX_UNAVAILABLE_TIMEOUT";
            default:
                return e.code() >= 500 && e.code() <= 599 ? "VERTEX_UNAVAILABLE_5XX" : CODE_UNEXPECTED;
        }
    }

    /** Solo operationData (sin credenciales por construccion) + contexto de catalogo, acotado. */
    private String buildUserPrompt(GovernorInput input) {
        String payload = input.event().operationData() != null
                ? input.event().operationData().toString()
                : "{}";
        if (payload.length() > props.getMaxInputChars()) {
            payload = payload.substring(0, props.getMaxInputChars());
        }
        String aggregateType = input.event().aggregateType();
        return "Operacion a enriquecer (JSON):\n" + payload
                + "\n\nContexto: catalog_version=" + support.catalogVersion()
                + ", target_table=" + support.targetTable(aggregateType)
                + ", eventType=" + input.event().eventType()
                + ". Tu respuesta es SOLO enriquecimiento: no decidas, no cambies el plan ni el SQL.";
    }

    private static String loadPrompt(String promptVersion) throws IOException {
        String resource = "prompts/" + promptVersion + ".txt";
        try (InputStream in = VertexGovernor.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing prompt resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class SchemaViolation extends RuntimeException {
        SchemaViolation(String message) {
            super(message);
        }
    }

    private static final class InvalidJson extends RuntimeException {
        InvalidJson(Throwable cause) {
            super(cause);
        }
    }
}