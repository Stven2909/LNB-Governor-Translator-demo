package com.pagaduriasintetica.worker.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketDataIntegrityException;
import com.pagaduriasintetica.worker.agent.exception.TicketDatabaseUnavailableException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsUnavailableException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import com.pagaduriasintetica.worker.contract.ticket.TicketProvenance;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.mode", havingValue = "HTTP")
public class HttpTicketQueryTool implements TicketQueryTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String lookupUrl;
    private final String defaultProvenance;
    private final String defaultSource;

    public HttpTicketQueryTool(TicketAgentProperties props) {
        if (!StringUtils.hasText(props.getBaseUrl())) {
            throw new IllegalStateException("app.ticket-agent.base-url requerido cuando mode=HTTP");
        }

        // base-url se recibe completo para poder apuntar a un mock local o al WS de Carlos.
        this.lookupUrl = props.getBaseUrl() + props.getLookupPath();
        this.defaultProvenance = parseProvenance(props.getProvenance()).name();
        this.defaultSource = parseSource(props.getSource());
        Duration timeout = parseTimeout(props.getTimeout());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Procedencia declarada por configuracion. Solo se usa cuando el WS no la envia: la
     * fuente real debe decirla, y si calla no se adivina. El valor por defecto es SYNTHETIC,
     * asi que para afirmar REAL hay que configurarlo de forma explicita contra el WS autorizado.
     */
    private static TicketProvenance parseProvenance(String value) {
        try {
            return TicketProvenance.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalStateException("app.ticket-agent.provenance invalida: " + value, e);
        }
    }

    private static String parseSource(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!"SYBASE".equals(normalized) && !"SEED".equals(normalized)) {
            throw new IllegalStateException("app.ticket-agent.source invalida: " + value);
        }
        return normalized;
    }

    private static Duration parseTimeout(String value) {
        try {
            Duration timeout = DurationStyle.detectAndParse(value);
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("debe ser mayor a cero");
            }
            return timeout;
        } catch (RuntimeException e) {
            throw new IllegalStateException("app.ticket-agent.timeout invalido: " + value, e);
        }
    }

    @Override
    public TicketToolResponse query(TicketKey key, String requestId) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("lotteryCode", key.lotteryCode());
            body.put("drawNumber", key.drawNumber());
            body.put("ticketNumber", key.ticketNumber());
            if (key.series() != null) body.put("series", key.series());
            if (key.checkDigit() != null) body.put("checkDigit", key.checkDigit());

            var response = restClient.post()
                    .uri(lookupUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Request-Id", requestId)
                    .header("X-Correlation-Id", requestId)
                    .body(serialize(body))
                    .retrieve()
                    .toEntity(TicketToolResponse.class)
                    .getBody();

            if (response == null) {
                throw new InvalidWsResponseException("Respuesta WS nula");
            }
            return withDefaults(response);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                // Un 404 solo es NOT_FOUND si el WS lo dice con un payload de negocio.
                // Un 404 de ruta equivocada no puede disfrazarse de "boleto no encontrado":
                // eso reportaria un dato falso con procedencia real.
                TicketToolResponse notFound = notFoundFromPayload(e.getResponseBodyAsString());
                if (notFound != null) {
                    return withDefaults(notFound);
                }
                throw new TicketWsException(
                        "404 del WS sin payload de negocio (found=false): no se traduce a NOT_FOUND", e);
            }
            // El estado del WS es informacion de negocio, no un detalle de transporte: 503
            // significa "no pude consultar" y 502 "el dato es inconsistente". Pero el codigo
            // del cuerpo es el que autoriza a afirmar la causa: un 503 de un proxy no es
            // DATABASE_UNAVAILABLE, y un 502 sin DATA_INTEGRITY_ERROR no es una inconsistencia
            // confirmada. Si el codigo no coincide, se conserva el estado con un codigo generico.
            String code = errorCode(e.getResponseBodyAsString());
            String detail = status + (StringUtils.hasText(code) ? " " + code : "");
            if (status == 503) {
                if ("DATABASE_UNAVAILABLE".equals(code)) {
                    throw new TicketDatabaseUnavailableException(
                            "WS de tickets sin base de datos disponible (" + detail + ")", e);
                }
                throw new TicketWsUnavailableException(
                        "WS de tickets no disponible (" + detail + ")", e);
            }
            if (status == 502) {
                if ("DATA_INTEGRITY_ERROR".equals(code)) {
                    throw new TicketDataIntegrityException(
                            "WS de tickets reporta inconsistencia de datos (" + detail + ")", e);
                }
                throw new TicketWsException("Error WS: " + detail, e);
            }
            throw new TicketWsException("Error WS: " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                throw new TicketTimeoutException("Timeout del WS de tickets", e);
            }
            throw new TicketWsException("Fallo de red al consultar WS de tickets", e);
        } catch (RestClientException e) {
            // Spring puede envolver errores de I/O como RestClientException generico.
            if (isTimeout(e)) {
                throw new TicketTimeoutException("Timeout del WS de tickets", e);
            }
            if (isNetworkFailure(e)) {
                throw new TicketWsException("Fallo de red al consultar WS de tickets", e);
            }
            throw new InvalidWsResponseException("Respuesta invalida del WS de tickets", e);
        }
    }

    /**
     * Extrae un NOT_FOUND de negocio solo si el cuerpo lo declara: debe ser un objeto JSON con
     * el campo "found" presente y en false. Cualquier otra cosa (cuerpo vacio, "{}", HTML de
     * un proxy, error de ruta) se considera ambigua y no se traduce.
     */
    private static TicketToolResponse notFoundFromPayload(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject() || !node.has("found")) {
                return null;
            }
            if (node.get("found").asBoolean(true)) {
                return null;
            }
            return JSON.treeToValue(node, TicketToolResponse.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Codigo de error declarado por el WS. El contrato canonico usa {@code error} para
     * 503/502 y {@code code} para 400; se aceptan ambos para no perder el codigo real.
     * Un cuerpo opaco o sin codigo devuelve {@code null}: el estado HTTP se conserva,
     * pero no se inventa una causa.
     */
    private static String errorCode(String body) {
        if (!StringUtils.hasText(body)) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || !node.isObject()) {
                return null;
            }
            for (String field : new String[]{"error", "code"}) {
                JsonNode value = node.get(field);
                if (value != null && value.isTextual() && StringUtils.hasText(value.asText())) {
                    return value.asText().trim();
                }
            }
        } catch (JsonProcessingException ignored) {
            // El estado HTTP ya es suficiente; no se inventa un codigo.
        }
        return null;
    }

    /**
     * La procedencia del payload manda; la configurada solo cubre el caso en que el WS calle.
     * Si el WS declara procedencia pero omite {@code source}, no se rellena: el agente debe
     * rechazar un REAL sin SYBASE en lugar de completarlo por conveniencia.
     */
    private TicketToolResponse withDefaults(TicketToolResponse response) {
        if (StringUtils.hasText(response.provenance())) {
            return response;
        }
        return new TicketToolResponse(
                response.found(),
                response.awarded(),
                response.ticketNumber(),
                response.prize(),
                response.collectionStatus(),
                defaultProvenance,
                StringUtils.hasText(response.source()) ? response.source() : defaultSource,
                response.databaseSource(),
                response.message());
    }

    private static String serialize(Map<String, Object> body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new TicketAgentException("No se pudo serializar la clave del boleto", e);
        }
    }

    private static boolean isNetworkFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.net.ConnectException
                    || current instanceof java.net.UnknownHostException
                    || current instanceof java.net.NoRouteToHostException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
