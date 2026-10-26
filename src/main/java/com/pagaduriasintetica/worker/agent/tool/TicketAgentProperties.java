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
    private String promptVersion = "ticket-agent-v0.1";

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

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
}
