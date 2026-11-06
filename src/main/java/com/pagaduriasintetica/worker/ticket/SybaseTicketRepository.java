package com.pagaduriasintetica.worker.ticket;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Repository
public class SybaseTicketRepository {

    private static final Logger log = LoggerFactory.getLogger(SybaseTicketRepository.class);

    public static final String DATABASE_SOURCE_LABEL = "Sybase ASE LNB DEV";

    private final SybaseTicketProperties properties;
    private final AtomicReference<DataSource> dataSourceRef = new AtomicReference<>();
    private final AtomicBoolean poolInitialized = new AtomicBoolean(false);
    private final AtomicReference<String> poolError = new AtomicReference<>("Pool no inicializado todavía");
    private final AtomicReference<String> lastQueryError = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastCheck = new AtomicReference<>(Instant.now());

    public SybaseTicketRepository(SybaseTicketProperties properties) {
        this.properties = properties;
        initPoolAsync();
    }

    private void initPoolAsync() {
        if (!properties.isEnabled()) {
            poolError.set("Sybase JDBC deshabilitado por configuración (app.sybase.ticket.enabled=false)");
            return;
        }

        Thread.ofVirtual().start(() -> {
            try {
                String driverClass = properties.getDriverClassName();
                String jdbcUrl = properties.getUrl();

                // Driver oficial jConnect (com.sybase.jdbc4.jdbc.SybDriver)
                try {
                    Class.forName(driverClass);
                    log.info("Driver oficial jConnect [{}] detectado en classpath", driverClass);
                } catch (ClassNotFoundException ex) {
                    String err = "Driver JDBC no encontrado. Instale jconn4.jar autorizado mediante: " +
                                 "mvn install:install-file -Dfile=jconn4.jar -DgroupId=com.sybase.jdbc4 -DartifactId=jconn4 -Dversion=16.0 -Dpackaging=jar";
                    poolError.set(err);
                    log.error(err);
                    return;
                }

                HikariConfig config = new HikariConfig();
                config.setJdbcUrl(jdbcUrl);
                config.setDriverClassName(driverClass);
                config.setUsername(properties.getUsername());
                config.setPassword(properties.getPassword());
                config.setMaximumPoolSize(properties.getPoolSize());
                config.setConnectionTimeout(properties.getConnectionTimeout().toMillis());
                config.setInitializationFailTimeout(properties.getInitializationFailTimeout().toMillis());
                config.setPoolName("SybaseTicketPool-Carlos");

                HikariDataSource ds = new HikariDataSource(config);
                dataSourceRef.set(ds);
                poolInitialized.set(true);
                poolError.set(null);
                lastCheck.set(Instant.now());
                log.info("Pool HikariCP configurado exitosamente con driver [{}] hacia: {}", driverClass, jdbcUrl);
            } catch (Exception e) {
                String rawMsg = e.getMessage() != null ? e.getMessage() : "Error de conexion";
                String sanitizedMsg = rawMsg.replaceAll("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?::\\d+)?\\b", "[redacted]");
                String msg = "Infraestructura Sybase DEV no alcanzable: " + sanitizedMsg;
                poolError.set(msg);
                lastCheck.set(Instant.now());
                log.warn(msg);
            }
        });
    }

    public TicketLookupResponse findTicket(String lotteryCode, String drawNumber, String ticketNumber) {
        lastCheck.set(Instant.now());

        // 1. Intentar consulta JDBC real si el pool está operativo
        DataSource ds = dataSourceRef.get();
        if (ds != null && poolInitialized.get()) {
            try (Connection conn = ds.getConnection()) {
                String sql = "SELECT ticket_number, awarded, prize_amount, prize_currency, prize_category, collection_status " +
                             "FROM " + properties.getTableName() + " " +
                             "WHERE lottery_code = ? AND draw_number = ? AND ticket_number = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, lotteryCode);
                    ps.setString(2, drawNumber);
                    ps.setString(3, ticketNumber);
                    try (ResultSet rs = ps.executeQuery()) {
                        lastQueryError.set(null);
                        if (rs.next()) {
                            boolean awarded = rs.getBoolean("awarded");
                            String collectionStatus = rs.getString("collection_status");
                            if (awarded) {
                                try {
                                    String normalizedAmount = normalizeAmount(rs.getString("prize_amount"));
                                    return TicketLookupResponse.winner(
                                            rs.getString("ticket_number"),
                                            normalizedAmount,
                                            rs.getString("prize_currency"),
                                            rs.getString("prize_category"),
                                            collectionStatus,
                                            "REAL",
                                            "SYBASE",
                                            DATABASE_SOURCE_LABEL
                                    );
                                } catch (SybaseDataIntegrityException ex) {
                                    lastQueryError.set("Error de integridad en datos de Sybase: " + ex.getMessage());
                                    throw ex;
                                }
                            } else {
                                return TicketLookupResponse.notAwarded(
                                        rs.getString("ticket_number"),
                                        collectionStatus,
                                        "REAL",
                                        "SYBASE",
                                        DATABASE_SOURCE_LABEL
                                );
                            }
                        } else {
                            return TicketLookupResponse.notFound(
                                    ticketNumber,
                                    "REAL",
                                    "SYBASE",
                                    DATABASE_SOURCE_LABEL
                            );
                        }
                    }
                }
            } catch (SQLException e) {
                String queryErrMsg = "Fallo al ejecutar consulta en Sybase: " + e.getMessage();
                lastQueryError.set(queryErrMsg);
                log.error("Consulta JDBC a Sybase falló: {}", e.getMessage());
                if (!properties.isSeedEnabled()) {
                    throw new SybaseDatabaseUnavailableException(queryErrMsg);
                }
            }
        }

        // 2. Comportamiento Fail Closed vs Semilla de pruebas
        if (properties.isSeedEnabled()) {
            // Solo si app.sybase.ticket.seed-enabled=true (entorno local de pruebas)
            return resolveAuthorizedSeed(lotteryCode, drawNumber, ticketNumber);
        }

        // FAIL CLOSED: Si el pool no está activo y la semilla está deshabilitada (default)
        throw new SybaseDatabaseUnavailableException(
                "Pool Sybase no disponible (pool no inicializado). Detalle: " + poolError.get()
        );
    }

    private TicketLookupResponse resolveAuthorizedSeed(String lotteryCode, String drawNumber, String ticketNumber) {
        String sourceInfo = "Fixture autorizado LNB DEV (Caso 1 de Alex/Danny) [Sybase pool: sin conexión VPN]";

        // Caso no encontrado
        if ("9999999999".equals(ticketNumber)) {
            return TicketLookupResponse.notFound(ticketNumber, "SYNTHETIC", "SEED", sourceInfo);
        }

        // Caso no premiado
        if ("0011111111".equals(ticketNumber)) {
            return TicketLookupResponse.notAwarded(ticketNumber, "SYNTHETIC", "SEED", sourceInfo);
        }

        // Caso 1: Boleto ganador de referencia oficial ($50.00 USD)
        return TicketLookupResponse.winner(
                ticketNumber != null && !ticketNumber.isBlank() ? ticketNumber : "0012345678",
                "50.00",
                "USD",
                "CASH",
                "PENDING",
                "SYNTHETIC",
                "SEED",
                sourceInfo
        );
    }

    static String normalizeAmount(String amountStr) {
        if (amountStr == null || amountStr.isBlank()) {
            throw new SybaseDataIntegrityException("prize_amount no puede ser nulo o vacío en un boleto premiado");
        }
        try {
            BigDecimal bd = new BigDecimal(amountStr.trim());
            if (bd.compareTo(BigDecimal.ZERO) <= 0) {
                throw new SybaseDataIntegrityException("prize_amount debe ser un valor estrictamente positivo mayor a 0.00 en un boleto premiado: '" + amountStr + "'");
            }
            // Soporta ceros irrelevantes a la derecha (ej. Sybase money 50.0000 -> 50.00) sin redondear dígitos significativos
            BigDecimal stripped = bd.stripTrailingZeros();
            if (stripped.scale() < 2) {
                return stripped.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
            } else if (stripped.scale() == 2) {
                return stripped.toPlainString();
            } else {
                throw new SybaseDataIntegrityException(
                        "prize_amount tiene más de 2 decimales significativos sin regla de redondeo autorizada: '" + amountStr + "'"
                );
            }
        } catch (SybaseDataIntegrityException e) {
            throw e;
        } catch (Exception e) {
            throw new SybaseDataIntegrityException("prize_amount inválido o no normalizable a decimal: '" + amountStr + "'", e);
        }
    }

    public boolean isPoolInitialized() {
        return poolInitialized.get();
    }

    public boolean isSeedEnabled() {
        return properties.isSeedEnabled();
    }

    public String getPoolError() {
        return poolError.get();
    }

    public String getLastQueryError() {
        return lastQueryError.get();
    }

    public String getLastError() {
        return poolError.get();
    }

    public Instant getLastCheck() {
        return lastCheck.get();
    }

    @PreDestroy
    public void close() {
        DataSource ds = dataSourceRef.get();
        if (ds instanceof HikariDataSource hds) {
            hds.close();
            log.info("Pool HikariCP para Sybase cerrado correctamente");
        }
    }
}
