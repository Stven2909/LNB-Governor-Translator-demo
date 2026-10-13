package com.pagaduriasintetica.worker.governor;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import com.google.genai.types.Type.Known;
import com.pagaduriasintetica.worker.config.WorkerProperties;

import java.util.List;
import java.util.Map;

/**
 * Adaptador real de Vertex AI sobre el SDK google-genai. Enfoques clave:
 * - Sin client-side retry (HttpRetryOptions.attempts=1): el reintento y la clasificacion viven en
 *   VertexGovernor/pubsub, no en el SDK (el SDK reintenta 5 veces por defecto y eso excede el
 *   presupuesto fail-fast de la entrega).
 * - Timeout explicito (HttpOptions.timeout) para no superar el ackDeadline en Cloud Run.
 * - project+location+vertexAI(true) con Application Default Credentials (ADC) del entorno de run.
 * - responseSchema estricto para que el modelo solo pueda emitir el JSON de enriquecimiento.
 */
public class GenaiVertexClient implements VertexClient {

    private final Client client;
    private final WorkerProperties.Vertex props;
    private final Schema enrichmentSchema;

    public GenaiVertexClient(WorkerProperties.Vertex props) {
        this.props = props;
        this.enrichmentSchema = buildEnrichmentSchema();
        this.client = Client.builder()
                .project(props.getProjectId())
                .location(props.getRegion())
                .vertexAI(true)
                .httpOptions(HttpOptions.builder()
                        .timeout(Math.toIntExact(props.getTimeout().getSeconds()))
                        .retryOptions(HttpRetryOptions.builder()
                                .attempts(1)
                                .build())
                        .build())
                .build();
    }

    @Override
    public VertexOutcome generate(String systemInstruction, String userPrompt) {
        String contentText = systemInstruction + "\n\n" + userPrompt;
        Content content = Content.builder()
                .role("user")
                .parts(List.of(Part.builder().text(contentText).build()))
                .build();
        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature((float) props.getTemperature())
                .responseMimeType("application/json")
                .responseSchema(enrichmentSchema)
                .maxOutputTokens(1024)
                .build();

        GenerateContentResponse response;
        try {
            response = client.models.generateContent(props.getModel(), content, config);
        } catch (ApiException e) {
            throw new VertexCallException(e.code(), e.status(), e.getMessage(), e);
        } catch (RuntimeException e) {
            // IO/timeout/estado inesperado del SDK: lo clasifica VertexGovernor como transitorio.
            throw new VertexCallException(-1, "io", "Vertex call failed: " + e, e);
        }

        String text;
        try {
            text = response.text();
        } catch (RuntimeException e) {
            return VertexOutcome.blocked(reasonName(response));
        }
        // knownEnum() distingue MAX_TOKENS (truncado: no valido, pero no es safety) de los
        // bloqueos reales (SAFETY, PROHIBITED_CONTENT, BLOCKLIST, RECITATION, ...). Comparar por
        // toString() colapsaba ambos casos en un falso "safety block".
        FinishReason.Known known =
                response.finishReason() != null ? response.finishReason().knownEnum() : null;
        String reasonName = known != null ? known.name() : reasonName(response);
        if (known == FinishReason.Known.MAX_TOKENS) {
            return VertexOutcome.truncated(reasonName);
        }
        boolean safetyBlocked = known != FinishReason.Known.STOP
                || response.promptFeedback()
                        .filter(fb -> fb.blockReason().isPresent())
                        .isPresent();
        if (safetyBlocked || text == null || text.isBlank()) {
            return VertexOutcome.blocked(reasonName);
        }
        return VertexOutcome.text(text);
    }

    /** Nombre del finishReason para trazabilidad cuando knownEnum() no esta disponible (null). */
    private static String reasonName(GenerateContentResponse response) {
        return response.finishReason() != null ? response.finishReason().toString() : null;
    }

    /** Mismo shape que valida VertexGovernor.parseAndValidate: summary, observations, suggested_mapping. */
    private static Schema buildEnrichmentSchema() {
        return Schema.builder()
                .type(Known.OBJECT)
                .title("GovernorEnrichment")
                .description("Enriquecimiento NO autoritativo. Nunca decide ni modifica el plan/SQL.")
                .properties(Map.of(
                        "summary", Schema.builder()
                                .type(Known.STRING)
                                .description("Resumen de maximo 2 frases.")
                                .build(),
                        "observations", Schema.builder()
                                .type(Known.ARRAY)
                                .items(Schema.builder().type(Known.STRING).build())
                                .build(),
                        "suggested_mapping", Schema.builder()
                                .type(Known.OBJECT)
                                .description("String-to-string opcional; no cambia el field_mapping.")
                                .build()))
                .required("summary", "observations")
                .build();
    }
}