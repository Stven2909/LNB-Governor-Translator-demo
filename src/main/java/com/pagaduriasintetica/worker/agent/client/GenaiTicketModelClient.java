package com.pagaduriasintetica.worker.agent.client;

import com.google.genai.Client;
import com.google.genai.types.*;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import com.pagaduriasintetica.worker.config.WorkerProperties;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.model-client", havingValue = "VERTEX")
public class GenaiTicketModelClient implements TicketModelClient {

    private static final Logger log = LoggerFactory.getLogger(GenaiTicketModelClient.class);

    private final Client client;
    private final WorkerProperties.Vertex vertexProps;
    private final TicketAgentProperties ticketProps;

    public GenaiTicketModelClient(WorkerProperties props, TicketAgentProperties ticketProps) {
        this.vertexProps = props.getVertex();
        this.ticketProps = ticketProps;
        if (!StringUtils.hasText(vertexProps.getProjectId())) {
            throw new IllegalStateException(
                    "app.vertex.project-id es obligatorio cuando APP_TICKET_AGENT_MODEL_CLIENT=VERTEX");
        }
        String location = StringUtils.hasText(ticketProps.getRegion())
                ? ticketProps.getRegion()
                : vertexProps.getRegion();
        log.info("TicketAgent VERTEX configurado: project={}, region={}, model={}",
                vertexProps.getProjectId(), location,
                StringUtils.hasText(ticketProps.getModel()) ? ticketProps.getModel() : vertexProps.getModel());
        this.client = Client.builder()
                .project(vertexProps.getProjectId())
                .location(location)
                .vertexAI(true)
                .build();
    }

    @Override
    public Map<String, Object> requestToolCall(TicketKey key, String traceId) {
        String model = StringUtils.hasText(ticketProps.getModel())
                ? ticketProps.getModel()
                : vertexProps.getModel();
        if (!StringUtils.hasText(model)) {
            throw new TicketAgentException("app.ticket-agent.model / app.vertex.model no configurado");
        }
        if (!StringUtils.hasText(vertexProps.getProjectId())) {
            throw new TicketAgentException("app.vertex.project-id requerido para VERTEX");
        }

        Schema params = Schema.builder()
                .type(Type.Known.OBJECT)
                .properties(Map.of(
                        "lotteryCode", Schema.builder().type(Type.Known.STRING).build(),
                        "drawNumber", Schema.builder().type(Type.Known.STRING).build(),
                        "ticketNumber", Schema.builder().type(Type.Known.STRING).build(),
                        "series", Schema.builder().type(Type.Known.STRING).build(),
                        "checkDigit", Schema.builder().type(Type.Known.STRING).build()
                ))
                .required("lotteryCode", "drawNumber", "ticketNumber")
                .build();

        FunctionDeclaration fn = FunctionDeclaration.builder()
                .name("consultar_boleto")
                .description("Consulta el boleto en el WS autorizado; devuelve found/awarded/prize/collectionStatus.")
                .parameters(params)
                .build();

        Tool tool = Tool.builder().functionDeclarations(List.of(fn)).build();

        ToolConfig toolConfig = ToolConfig.builder()
                .functionCallingConfig(FunctionCallingConfig.builder()
                        .mode(FunctionCallingConfigMode.Known.ANY)
                        .build())
                .build();

        StringBuilder prompt = new StringBuilder()
                .append("Consulta el boleto usando la herramienta consultar_boleto.\n")
                .append("Usa exactamente estos valores, sin modificarlos ni inventar otros:\n")
                .append("lotteryCode=").append(key.lotteryCode()).append('\n')
                .append("drawNumber=").append(key.drawNumber()).append('\n')
                .append("ticketNumber=").append(key.ticketNumber()).append('\n');
        if (StringUtils.hasText(key.series())) {
            prompt.append("series=").append(key.series()).append('\n');
        }
        if (StringUtils.hasText(key.checkDigit())) {
            prompt.append("checkDigit=").append(key.checkDigit()).append('\n');
        }
        prompt.append("traceId=").append(traceId);

        Content user = Content.builder()
                .role("user")
                .parts(List.of(Part.fromText(prompt.toString())))
                .build();

        GenerateContentConfig config = GenerateContentConfig.builder()
                .temperature(0.0f)
                .tools(List.of(tool))
                .toolConfig(toolConfig)
                .build();

        try {
            GenerateContentResponse response = client.models.generateContent(model, user, config);
            var calls = response.functionCalls();
            if (calls == null || calls.isEmpty()) {
                throw new TicketAgentException("El modelo no invocó consultar_boleto");
            }
            FunctionCall fc = calls.get(0);
            var args = fc.args().orElseThrow(() -> new TicketAgentException("FunctionCall sin args"));
            log.debug("FunctionCall name={} args={}", fc.name().orElse("?"), args);
            Map<String, Object> out = new LinkedHashMap<>();
            args.forEach((k, v) -> {
                if (v != null) out.put(k, v.toString());
            });
            return out;
        } catch (TicketAgentException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Fallo invocando Vertex para ticket-agent", e);
            throw new TicketAgentException("Error al invocar Vertex para ticket-agent: " + e.getMessage(), e);
        }
    }
}
