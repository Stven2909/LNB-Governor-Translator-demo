package com.pagaduriasintetica.worker.agent.tool;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.ticket-agent")
public class TicketAgentProperties {

    private String mode = "FIXTURE";
    private String modelClient = "FAKE";
    private String model = "gemini-3.8-flash";
    private String region = "global";
    private String baseUrl = "";
    private String lookupPath = "/api/v1/tickets/lookup";
    private String timeout = "5s";
    private String provenance = "SYNTHETIC";
    /** Origen declarado cuando el WS no lo envia: vacio, SYBASE o SEED. */
    private String source = "";
    private String promptVersion = "ticket-agent-v0.1";

    /**
     * Si es true, cada consulta viaja con {@code Authorization: Bearer <ID token>}. Se usa
     * cuando el WS esta en Cloud Run privado: sin token el servicio responde 403 antes de
     * ejecutar codigo. Desactivado por defecto para que el entorno local no dependa de GCP.
     */
    private boolean authEnabled = false;

    /**
     * Audiencia del ID token. Vacio significa "usar {@code base-url}". Cloud Run exige que la
     * audiencia sea exactamente la URL del servicio ({@code https://servicio-xxxx.a.run.app}),
     * no un dominio propio: si no coincide, responde 401.
     */
    private String authAudience = "";

    /**
     * Token estatico para pruebas manuales contra un WS privado desde fuera de GCP. Vacio
     * significa "pedirlo al metadata server". No se usa cuando {@code auth-enabled=false}.
     */
    private String authToken = "";

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getModelClient() { return modelClient; }
    public void setModelClient(String modelClient) { this.modelClient = modelClient; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getLookupPath() { return lookupPath; }
    public void setLookupPath(String lookupPath) { this.lookupPath = lookupPath; }

    public String getTimeout() { return timeout; }
    public void setTimeout(String timeout) { this.timeout = timeout; }

    public String getProvenance() { return provenance; }
    public void setProvenance(String provenance) { this.provenance = provenance; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public boolean isAuthEnabled() { return authEnabled; }
    public void setAuthEnabled(boolean authEnabled) { this.authEnabled = authEnabled; }

    public String getAuthAudience() { return authAudience; }
    public void setAuthAudience(String authAudience) { this.authAudience = authAudience; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
}
