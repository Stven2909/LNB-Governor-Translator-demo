package com.pagaduriasintetica.worker.agent.tool;

import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.ticket-agent.mode", havingValue = "HTTP")
public class HttpTicketQueryTool implements TicketQueryTool {

    private final RestClient restClient;
    private final TicketAgentProperties props;

    public HttpTicketQueryTool(RestClient.Builder restClientBuilder, TicketAgentProperties props) {
        this.restClient = restClientBuilder.build();
        this.props = props;
        if (!StringUtils.hasText(props.getBaseUrl())) {
            throw new IllegalStateException("app.ticket-agent.base-url requerido cuando mode=HTTP");
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
                    .uri(props.getBaseUrl() + props.getLookupPath())
                    .header("X-Request-Id", requestId)
                    .body(body)
                    .retrieve()
                    .toEntity(TicketToolResponse.class)
                    .getBody();

            if (response == null) {
                throw new TicketWsException("WS response is null");
            }
            return response;
        } catch (ResourceAccessException e) {
            throw new TicketTimeoutException("Timeout o error de red al consultar WS de tickets", e);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                return new TicketToolResponse(false, false, key.ticketNumber(), null, null, "REAL");
            }
            throw new TicketWsException("Error WS: " + e.getStatusCode(), e);
        }
    }
}
