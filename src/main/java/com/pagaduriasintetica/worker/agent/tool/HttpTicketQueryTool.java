package com.pagaduriasintetica.worker.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
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
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.mode", havingValue = "HTTP")
public class HttpTicketQueryTool implements TicketQueryTool {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final String lookupUrl;

    public HttpTicketQueryTool(TicketAgentProperties props) {
        if (!StringUtils.hasText(props.getBaseUrl())) {
            throw new IllegalStateException("app.ticket-agent.base-url requerido cuando mode=HTTP");
        }

        // base-url se recibe completo para poder apuntar a un mock local o al WS de Carlos.
        this.lookupUrl = props.getBaseUrl() + props.getLookupPath();
        Duration timeout = parseTimeout(props.getTimeout());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeout);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
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
                    .body(serialize(body))
                    .retrieve()
                    .toEntity(TicketToolResponse.class)
                    .getBody();

            if (response == null) {
                throw new InvalidWsResponseException("Respuesta WS nula");
            }
            return response;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return new TicketToolResponse(false, false, key.ticketNumber(), null, null, "REAL");
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
