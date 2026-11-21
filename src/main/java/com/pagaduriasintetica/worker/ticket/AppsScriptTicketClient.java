package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * Cliente HTTP adaptador para consultar el servicio temporal Google Apps Script
 * como fuente sintética autorizada para el WS de consulta de boletos.
 */
@Component
public class AppsScriptTicketClient {

    private static final Logger log = LoggerFactory.getLogger(AppsScriptTicketClient.class);
    public static final String DATABASE_SOURCE_LABEL = "Google Apps Script LNB DEV";

    private final RestClient restClient;
    private final AppsScriptTicketProperties properties;

    @Autowired
    public AppsScriptTicketClient(AppsScriptTicketProperties properties) {
        this(createDefaultRestClient(properties), properties);
    }

    public AppsScriptTicketClient(RestClient restClient, AppsScriptTicketProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static RestClient createDefaultRestClient(AppsScriptTicketProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration connectTimeout = properties.getAppsScript().getConnectTimeout();
        Duration readTimeout = properties.getAppsScript().getReadTimeout();
        factory.setConnectTimeout(connectTimeout != null ? connectTimeout : Duration.ofSeconds(3));
        factory.setReadTimeout(readTimeout != null ? readTimeout : Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(factory).build();
    }

    public TicketLookupResponse findTicket(TicketKey key, String correlationId) {
        if (key == null) {
            throw new IllegalArgumentException("TicketKey no puede ser nula");
        }

        String rawLottery = key.lotteryCode();
        String upstreamLottery = rawLottery;
        if ("LOTÍN".equalsIgnoreCase(rawLottery)) {
            log.info("[correlationId={}] normalizado LOTÍN -> LOTIN para upstream", correlationId);
            upstreamLottery = "LOTIN";
        }

        String baseUrl = properties.getAppsScript().getUrl();
        if (!StringUtils.hasText(baseUrl)) {
            throw new AppsScriptException("app.ticket.apps-script.url no configurada");
        }

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(baseUrl.trim())
                .queryParam("lotteryCode", upstreamLottery)
                .queryParam("drawNumber", key.drawNumber())
                .queryParam("ticketNumber", key.ticketNumber());

        if (StringUtils.hasText(correlationId)) {
            uriBuilder.queryParam("requestId", correlationId.trim());
        }

        URI uri = uriBuilder.build().toUri();

        AppsScriptUpstreamResponse response;
        try {
            response = restClient.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(AppsScriptUpstreamResponse.class);
        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                log.error("[correlationId={}] Timeout al consultar Apps Script: {}", correlationId, e.getMessage());
                throw new AppsScriptTimeoutException("Timeout al consultar Apps Script", e);
            }
            log.error("[correlationId={}] Fallo de red al consultar Apps Script: {}", correlationId, e.getMessage());
            throw new AppsScriptException("Fallo de red al consultar Apps Script: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            log.error("[correlationId={}] Error HTTP {} de Apps Script: {}", correlationId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new AppsScriptException("HTTP " + e.getStatusCode().value() + " de Apps Script", e);
        } catch (RestClientException e) {
            if (isTimeout(e)) {
                log.error("[correlationId={}] Timeout al consultar Apps Script: {}", correlationId, e.getMessage());
                throw new AppsScriptTimeoutException("Timeout al consultar Apps Script", e);
            }
            log.error("[correlationId={}] Fallo al consultar Apps Script: {}", correlationId, e.getMessage());
            throw new AppsScriptException("Fallo al consultar Apps Script: " + e.getMessage(), e);
        }

        return mapResponse(response, key, correlationId);
    }

    private TicketLookupResponse mapResponse(AppsScriptUpstreamResponse response, TicketKey key, String correlationId) {
        if (response == null || response.found() == null) {
            throw new AppsScriptDataIntegrityException("Respuesta nula o malformada de Apps Script");
        }

        boolean found = Boolean.TRUE.equals(response.found());
        boolean awarded = Boolean.TRUE.equals(response.awarded());

        if (!found) {
            if (awarded || response.prize() != null) {
                throw new AppsScriptDataIntegrityException("NOT_FOUND inconsistente con awarded=true o prize no nulo");
            }
            return TicketLookupResponse.notFound(
                    key.ticketNumber(),
                    "SYNTHETIC",
                    TicketSource.APPS_SCRIPT.name(),
                    DATABASE_SOURCE_LABEL
            );
        }

        if (!awarded) {
            if (response.prize() != null) {
                throw new AppsScriptDataIntegrityException("NOT_AWARDED inconsistente con prize no nulo");
            }
            return TicketLookupResponse.notAwarded(
                    key.ticketNumber(),
                    response.collectionStatus(),
                    "SYNTHETIC",
                    TicketSource.APPS_SCRIPT.name(),
                    DATABASE_SOURCE_LABEL
            );
        }

        // Caso premiado: found=true, awarded=true
        if (response.prize() == null || !StringUtils.hasText(response.prize().amount())) {
            throw new AppsScriptDataIntegrityException("Boleto premiado sin datos de premio válidos");
        }

        String normalizedAmount = SybaseTicketRepository.normalizeAmount(response.prize().amount());
        String currency = StringUtils.hasText(response.prize().currency()) ? response.prize().currency() : "USD";
        String category = StringUtils.hasText(response.prize().category()) ? response.prize().category() : "CASH";

        return TicketLookupResponse.winner(
                key.ticketNumber(),
                normalizedAmount,
                currency,
                category,
                response.collectionStatus(),
                "SYNTHETIC",
                TicketSource.APPS_SCRIPT.name(),
                DATABASE_SOURCE_LABEL
        );
    }

    private static boolean isTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SocketTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof TimeoutException) {
                return true;
            }
            if (current.getMessage() != null) {
                String msg = current.getMessage().toLowerCase();
                if (msg.contains("timed out") || msg.contains("timeout")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
