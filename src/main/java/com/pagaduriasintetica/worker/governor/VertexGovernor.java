package com.pagaduriasintetica.worker.governor;

import com.pagaduriasintetica.worker.config.WorkerProperties;
import com.pagaduriasintetica.worker.contract.GovernorContract;
import com.pagaduriasintetica.worker.contract.GovernorDecision;
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
 * Gobernador real de E3.2 (fail-closed, Q1-Q3 JD): Vertex AI ejerce VETO RESTRICTIVO sobre la
 * continuidad hacia la integracion legacy (prompt governor-v0.2 + responseSchema con decision).
 * Vertex NUNCA autoriza pagos y NUNCA modifica plan/SQL: la decision APPROVED y el plan vienen
 * del deterministico (catalogo congelado via DeterministicGovernorSupport); Vertex solo puede
 * vetar (REJECTED) o enriquecer. Los errores tecnicos NO son decisiones: se lanzan como
 * excepciones tipadas y el Worker los clasifica sin ejecutar JDBC.
 *
 * Clasificacion (el Worker mapea cada rama; ver WorkerService.invokeGovernor):
 * - 401/403/404 -> GovernorConfigurationException -> BLOCKED_CONFIGURATION (ACK terminal).
 * - 408/429/5xx/IO/timeout/MAX_TOKENS/inesperado -> GovernorUnavailableException ->
 *   RETRYABLE + NACK (Pub/Sub reintenta con backoff; al agotar, DLQ nativa). Sin cap exacto
 *   en codigo: el limite de entregas es aproximado (Q2 JD).
 * - safety block / promptFeedback con blockReason / texto vacio / JSON malformado /
 *   responseSchema violado / decision desconocida -> GovernorInvalidResponseException ->
 *   DLQ_QUARANTINED funcional + ACK (reintentar no ayudaria).
 * - Veredicto REJECTED de Vertex -> contrato REJECTED (veto): el Worker persiste motivo,
 *   responde ACK y jamas ejecuta JDBC. El pago confirmado NO se revierte.
 */
public class VertexGovernor implements Governor {

    private static final Logger log = LoggerFactory.getLogger(VertexGovernor.class);

    static final String CODE_SCHEMA_VIOLATION = "ENRICHMENT_SCHEMA_VIOLATION";
    static final String CODE_INVALID = "ENRICHMENT_INVALID";
    static final String CODE_SAFETY_BLOCK = "ENRICHMENT_SAFETY_BLOCK";
    static final String CODE_TRUNCATED = "ENRICHMENT_TRUNCATED";
    static final String CODE_EMPTY = "ENRICHMENT_EMPTY";
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

    /** Veredicto de continuidad (v0.2) + contexto de la operacion para el veto. */
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
            throw new GovernorUnavailableException(
                    "Vertex transitorio (" + codeForTransient(e) + "): " + e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Vertex inesperado para operationId={}", input.event().operationId(), e);
            throw new GovernorUnavailableException("Vertex inesperado: " + e, e);
        }

        if (outcome == null) {
            throw new GovernorInvalidResponseException(
                    "Vertex sin resultado (" + CODE_SAFETY_BLOCK + ")");
        }
        if (outcome.truncated()) {
            // MAX_TOKENS: insuficiencia de presupuesto, no bloqueo ni corrupcion. Reintento
            // via NACK; si persiste, la DLQ nativa lo absorbe (filosofia Q2 JD).
            throw new GovernorUnavailableException(
                    "Vertex truncado MAX_TOKENS (" + CODE_TRUNCATED + "): reintento programado");
        }
        if (outcome.safetyBlocked()) {
            throw new GovernorInvalidResponseException(
                    "Vertex bloqueado por seguridad (" + CODE_SAFETY_BLOCK + ")");
        }
        String text = outcome.text();
        if (text == null || text.isBlank()) {
            throw new GovernorInvalidResponseException(
                    "Vertex sin contenido (" + CODE_EMPTY + ")");
        }
        return verdict(input, text);
    }

    /** Veredicto APPROVED (deterministico + enrichment) o veto REJECTED (solo restrictivo). */
    private GovernorContract verdict(GovernorInput input, String text) {
        ParsedVerdict parsed;
        try {
            parsed = parseAndValidate(text);
        } catch (SchemaViolation e) {
            throw new GovernorInvalidResponseException(
                    "Vertex schema violado (" + CODE_SCHEMA_VIOLATION + "): " + e.getMessage(), e);
        } catch (InvalidJson e) {
            throw new GovernorInvalidResponseException(
                    "Vertex JSON malformado (" + CODE_INVALID + ")", e.getCause());
        }
        if (parsed.decision() == GovernorDecision.REJECTED) {
            log.warn("Vertex veto operationId={} reason={}", input.event().operationId(), parsed.reason());
            return support.reject(input, parsed.reason(), List.of("vertex_veto_semantic"),
                    parsed.enrichment());
        }
        return support.approve(input, GovernorEnrichmentStatus.ATTACHED, parsed.enrichment());
    }

    /**
     * Veredicto v0.2: decision APPROVED/REJECTED (solo restrictiva) + reason auditable +
     * enriquecimiento adjunto. JSON invalido vs JSON valido que viola el responseSchema:
     * codigos distinguibles en el mensaje de la excepcion.
     */
    private ParsedVerdict parseAndValidate(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            throw new InvalidJson(e);
        }
        if (root == null || !root.isObject()) {
            throw new SchemaViolation("root is not a JSON object");
        }
        JsonNode decision = root.get("decision");
        if (decision == null || !decision.isTextual()) {
            throw new SchemaViolation("required string field 'decision' missing");
        }
        GovernorDecision verdict;
        try {
            verdict = GovernorDecision.valueOf(decision.asText());
        } catch (IllegalArgumentException e) {
            throw new SchemaViolation("field 'decision' must be APPROVED or REJECTED");
        }
        JsonNode reason = root.get("reason");
        if (reason == null || !reason.isTextual() || reason.asText().isBlank()) {
            throw new SchemaViolation("required string field 'reason' missing or empty");
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
        return new ParsedVerdict(verdict, reason.asText(),
                GovernorEnrichment.attached(summary.asText(), obs, suggested));
    }

    /** Veredicto parseado: decision restrictiva + motivo + enriquecimiento adjunto. */
    private record ParsedVerdict(GovernorDecision decision, String reason,
                                 GovernorEnrichment enrichment) {
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
        return "Operacion a calificar (JSON):\n" + payload
                + "\n\nContexto: catalog_version=" + support.catalogVersion()
                + ", target_table=" + support.targetTable(aggregateType)
                + ", eventType=" + input.event().eventType()
                + ". Tu respuesta es el veredicto de continuidad (decision+reason) con "
                + "enriquecimiento; no cambies el plan ni el SQL.";
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