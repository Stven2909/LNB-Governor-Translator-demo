package com.pagaduriasintetica.worker.ticket;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "app.sybase.ticket")
public class SybaseTicketProperties {

    private boolean enabled = true;
    private String url = "jdbc:sybase:Tds:192.168.2.14:5000/lnb";
    private String driverClassName = "com.sybase.jdbc4.jdbc.SybDriver";
    private String username = "sa_lnb";
    private String password = "";
    private int poolSize = 5;
    private Duration connectionTimeout = Duration.ofSeconds(3);
    private String tableName = "LOTTERY_TICKETS";
    private String defaultProvenance = "REAL";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getDriverClassName() { return driverClassName; }
    public void setDriverClassName(String driverClassName) { this.driverClassName = driverClassName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int getPoolSize() { return poolSize; }
    public void setPoolSize(int poolSize) { this.poolSize = poolSize; }

    public Duration getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(Duration connectionTimeout) { this.connectionTimeout = connectionTimeout; }

    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }

    public String getDefaultProvenance() { return defaultProvenance; }
    public void setDefaultProvenance(String defaultProvenance) { this.defaultProvenance = defaultProvenance; }
}
