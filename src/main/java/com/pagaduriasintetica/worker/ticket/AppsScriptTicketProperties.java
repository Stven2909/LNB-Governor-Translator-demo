package com.pagaduriasintetica.worker.ticket;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.ticket")
public class AppsScriptTicketProperties {

    private static final Logger log = LoggerFactory.getLogger(AppsScriptTicketProperties.class);

    private String source;
    private final AppsScriptConfig appsScript = new AppsScriptConfig();
    private final SybaseTicketProperties sybaseProperties;

    public AppsScriptTicketProperties() {
        this(null);
    }

    @Autowired
    public AppsScriptTicketProperties(@Autowired(required = false) SybaseTicketProperties sybaseProperties) {
        this.sybaseProperties = sybaseProperties;
    }

    public static class AppsScriptConfig {
        private String url = "https://script.google.com/macros/s/AKfycbydXVwCmBLwJGREcjGnJ_iYEDEGmUYJGNKth4dlFQS0-T8IPZm9orID10PoW1afaB8ctA/exec";
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration readTimeout = Duration.ofSeconds(8);

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public Duration getConnectTimeout() {
            return connectTimeout;
        }

        public void setConnectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
        }

        public Duration getReadTimeout() {
            return readTimeout;
        }

        public void setReadTimeout(Duration readTimeout) {
            this.readTimeout = readTimeout;
        }
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public AppsScriptConfig getAppsScript() {
        return appsScript;
    }

    /**
     * Resuelve la fuente efectiva para el WS de consulta de boletos:
     * 1. Si app.ticket.source está configurado explícitamente, se usa ese valor.
     * 2. Fallback a las banderas heredadas:
     *    - Si app.sybase.ticket.enabled=true -> SYBASE
     *    - Si app.sybase.ticket.seed-enabled=true -> SEED
     *    - Por defecto -> SEED
     */
    public TicketSource resolveEffectiveSource() {
        if (StringUtils.hasText(source)) {
            try {
                return TicketSource.valueOf(source.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("app.ticket.source inválido: '" + source + "'. Valores permitidos: SEED, SYBASE, APPS_SCRIPT", e);
            }
        }
        if (sybaseProperties != null && sybaseProperties.isEnabled()) {
            return TicketSource.SYBASE;
        }
        if (sybaseProperties != null && sybaseProperties.isSeedEnabled()) {
            return TicketSource.SEED;
        }
        return TicketSource.SEED;
    }

    @PostConstruct
    public void validateAndLog() {
        TicketSource effective = resolveEffectiveSource();
        if (effective == TicketSource.APPS_SCRIPT) {
            if (!StringUtils.hasText(appsScript.getUrl())) {
                throw new IllegalStateException("app.ticket.apps-script.url es obligatoria cuando app.ticket.source=APPS_SCRIPT");
            }
            log.info("ticket source = APPS_SCRIPT, upstream = {}", appsScript.getUrl().trim());
        } else if (effective == TicketSource.SYBASE) {
            String sybUrl = sybaseProperties != null ? sybaseProperties.getUrl() : "";
            log.info("ticket source = SYBASE, upstream = {}", sybUrl);
        } else {
            log.info("ticket source = SEED");
        }
    }
}
