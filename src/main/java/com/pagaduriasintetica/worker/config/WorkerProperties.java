package com.pagaduriasintetica.worker.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Configuración del Worker por perfiles (test / dev-mock / dev-vertex / dev-sybase). Existe para
 * que Cloud Run se pueda desplegar sin recompilar: cada componente se selecciona por enum y el
 * resto de la configuración se inyecta por variable de entorno.
 *
 * Reglas (revisión de Carlos):
 * - Tipos fuertes y enums: no hay cadenas interpretadas en runtime.
 * - Fallo rápido: los campos obligatorios se validan al arrancar (jakarta.validation).
 * - Sin secretos: solo referencias (nombres de variable de entorno de Secret Manager), nunca
 *   credenciales ni tokens. Los valores se resuelven en tiempo de ejecucion, no se persisten.
 * - Valores por defecto seguros para tests: el perfil mock funciona sin configurar nada.
 */
@Validated
@ConfigurationProperties(prefix = "app")
public class WorkerProperties {

    @NotNull
    private Mode governor = Mode.MOCK;

    @NotNull
    private Mode ingest = Mode.PUSH;

    @NotNull
    private Mode store = Mode.MEMORY;

    @NotNull
    private Mode reporter = Mode.MOCK;

    @Valid
    @NotNull
    private Vertex vertex = new Vertex();

    @Valid
    @NotNull
    private Report report = new Report();

    @Valid
    @NotNull
    private Jdbc jdbc = new Jdbc();

    @Valid
    @NotNull
    private PubSub pubsub = new PubSub();

    /**
     * Limite de reintentos para un error NO PREVISTO antes del commit (bug de programacion,
     * dependencia caida). Sin este limite, la rama "excepcion no prevista -> RETRYABLE" hace
     * NACK indefinido y Pub/Sub entra en un ciclo perpetuo (revision de Carlos, Bloque 1).
     * Al agotarlo, la operacion va a QUARANTINE_TECHNICAL con ACK y se detiene el ciclo.
     *
     * Se cuentan los intentos del pipeline (attemptCount), que crecen en cada redelivery.
     */
    @Min(1)
    @Max(20)
    private int maxUnknownRetries = 3;

    /**
     * Limite de reintentos del REPORTE con el commit ya confirmado. Cuenta las redelivery de
     * Pub/Sub (deliveryAttempt), no los intentos del pipeline, para no alterar el attemptNumber
     * que viaja en el reporte. Al agotarlo NO se pone la operacion en cuarentena tecnica: el pago
     * en Sybase ya ocurrio, asi que queda reportStatus=BLOCKED + manualActionRequired=true y se
     * responde ACK para sacar el mensaje de la suscripcion.
     */
    @Min(1)
    @Max(20)
    private int maxReportAttempts = 5;

    /**
     * Seleccion de implementacion por componente. MOCK/MEMORY son los valores de la PoC;
     * VERTEX/HTTP/JDBC activan las integraciones reales de DEV.
     */
    public enum Mode {
        MOCK,
        VERTEX,
        HTTP,
        JDBC,
        MEMORY,
        PUSH,
        PULL
    }

    /**
     * Vertex AI. Los timeouts son explicitos: sin ellos el SDK usa valores que pueden exceder el
     * limite de la request de Cloud Run y provocar NACK en cascada.
     */
    public static class Vertex {

        @NotNull
        private String projectId = "";

        @NotNull
        private String region = "us-central1";

        @NotNull
        private String model = "gemini-2.5-flash";

        /** Identifica el prompt en logs y evidencia; se incrementa al cambiar el prompt. */
        @NotNull
        private String promptVersion = "governor-v0.1";

        @NotNull
        private Duration timeout = Duration.ofSeconds(30);

        @Min(1)
        @Max(10)
        private int maxAttempts = 3;

        @NotNull
        private Duration backoffBase = Duration.ofMillis(500);

        /** Limite de tamano del payload enviado al prompt. */
        @Min(1024)
        private int maxInputChars = 65536;

        /** Temperatura 0: el Gobernador no debe inventar; el catalogo es la autoridad. */
        @PositiveOrZero
        private double temperature = 0.0d;

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getPromptVersion() {
            return promptVersion;
        }

        public void setPromptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBackoffBase() {
            return backoffBase;
        }

        public void setBackoffBase(Duration backoffBase) {
            this.backoffBase = backoffBase;
        }

        public int getMaxInputChars() {
            return maxInputChars;
        }

        public void setMaxInputChars(int maxInputChars) {
            this.maxInputChars = maxInputChars;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }
    }

    /** Reporte del resultado a la API. El token NUNCA vive aqui: se referencia por variable de entorno. */
    public static class Report {

        @NotNull
        private String baseUrl = "";

        @NotNull
        private String resultPath = "/api/v1/integration/payment-committed";

        @NotNull
        private Duration timeout = Duration.ofSeconds(10);

        /**
         * Cabecera de idempotencia estable. Carlos: la clave es eventId; attemptNumber solo sirve
         * para trazabilidad, nunca como unica clave de idempotencia.
         */
        @NotNull
        private String idempotencyHeader = "Idempotency-Key";

        /** Nombre de la variable de entorno que contiene el token; el valor se resuelve en runtime. */
        @NotNull
        private String tokenEnvVar = "REPORT_API_TOKEN";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getResultPath() {
            return resultPath;
        }

        public void setResultPath(String resultPath) {
            this.resultPath = resultPath;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getIdempotencyHeader() {
            return idempotencyHeader;
        }

        public void setIdempotencyHeader(String idempotencyHeader) {
            this.idempotencyHeader = idempotencyHeader;
        }

        public String getTokenEnvVar() {
            return tokenEnvVar;
        }

        public void setTokenEnvVar(String tokenEnvVar) {
            this.tokenEnvVar = tokenEnvVar;
        }
    }

    /**
     * Sybase por JDBC (jConnect). El driver es dependencia externa bloqueada: se solicita a
     * Alex o al repositorio privado autorizado, nunca a un coordinate Maven inventado.
     */
    public static class Jdbc {

        @NotNull
        private String url = "";

        @NotNull
        private String driverClassName = "com.sybase.jdbc4.jdbc.SybDriver";

        @NotNull
        private String username = "";

        /** Referencia al secreto; el valor se resuelve en runtime desde Secret Manager. */
        @NotNull
        private String passwordEnvVar = "SYBASE_PASSWORD";

        @NotNull
        private Duration timeout = Duration.ofSeconds(15);

        /**
         * Estado JDBC desconocido (UNKNOWN) NUNCA se reejecuta automaticamente: pasa a
         * IN_DOUBT y se concilia por consulta de existencia en Sybase.
         */
        private boolean retryOnUnknown = false;

        @Min(1)
        @Max(10)
        private int maxAttempts = 3;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getDriverClassName() {
            return driverClassName;
        }

        public void setDriverClassName(String driverClassName) {
            this.driverClassName = driverClassName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPasswordEnvVar() {
            return passwordEnvVar;
        }

        public void setPasswordEnvVar(String passwordEnvVar) {
            this.passwordEnvVar = passwordEnvVar;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public boolean isRetryOnUnknown() {
            return retryOnUnknown;
        }

        public void setRetryOnUnknown(boolean retryOnUnknown) {
            this.retryOnUnknown = retryOnUnknown;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }

    /**
     * Pub/Sub. Push es el modelo objetivo de Cloud Run (request-driven); pull se usa solo en
     * pruebas locales, porque un hilo consumidor permanente no es el modelo de ejecucion de
     * Cloud Run. En push, la cuenta de Pub/Sub necesita run.invoker (dependencia de JD/LNB).
     */
    public static class PubSub {

        @NotNull
        private String projectId = "";

        @NotNull
        private String subscriptionId = "";

        /** Topic de la DLQ NATIVA de Pub/Sub: solo fallos tecnicos agotados, no cuarentena funcional. */
        @NotNull
        private String deadLetterTopicId = "";

        @NotNull
        private Duration ackDeadline = Duration.ofSeconds(60);

        @Min(0)
        private int maxDeliveryAttempts = 5;

        public String getProjectId() {
            return projectId;
        }

        public void setProjectId(String projectId) {
            this.projectId = projectId;
        }

        public String getSubscriptionId() {
            return subscriptionId;
        }

        public void setSubscriptionId(String subscriptionId) {
            this.subscriptionId = subscriptionId;
        }

        public String getDeadLetterTopicId() {
            return deadLetterTopicId;
        }

        public void setDeadLetterTopicId(String deadLetterTopicId) {
            this.deadLetterTopicId = deadLetterTopicId;
        }

        public Duration getAckDeadline() {
            return ackDeadline;
        }

        public void setAckDeadline(Duration ackDeadline) {
            this.ackDeadline = ackDeadline;
        }

        public int getMaxDeliveryAttempts() {
            return maxDeliveryAttempts;
        }

        public void setMaxDeliveryAttempts(int maxDeliveryAttempts) {
            this.maxDeliveryAttempts = maxDeliveryAttempts;
        }
    }

    public Mode getGovernor() {
        return governor;
    }

    public void setGovernor(Mode governor) {
        this.governor = governor;
    }

    public Mode getIngest() {
        return ingest;
    }

    public void setIngest(Mode ingest) {
        this.ingest = ingest;
    }

    public Mode getStore() {
        return store;
    }

    public void setStore(Mode store) {
        this.store = store;
    }

    public Mode getReporter() {
        return reporter;
    }

    public void setReporter(Mode reporter) {
        this.reporter = reporter;
    }

    public Vertex getVertex() {
        return vertex;
    }

    public void setVertex(Vertex vertex) {
        this.vertex = vertex;
    }

    public Report getReport() {
        return report;
    }

    public void setReport(Report report) {
        this.report = report;
    }

    public Jdbc getJdbc() {
        return jdbc;
    }

    public void setJdbc(Jdbc jdbc) {
        this.jdbc = jdbc;
    }

    public PubSub getPubsub() {
        return pubsub;
    }

    public void setPubsub(PubSub pubsub) {
        this.pubsub = pubsub;
    }

    public int getMaxUnknownRetries() {
        return maxUnknownRetries;
    }

    public void setMaxUnknownRetries(int maxUnknownRetries) {
        this.maxUnknownRetries = maxUnknownRetries;
    }

    public int getMaxReportAttempts() {
        return maxReportAttempts;
    }

    public void setMaxReportAttempts(int maxReportAttempts) {
        this.maxReportAttempts = maxReportAttempts;
    }

    /**
     * Verifica coherencia entre el modo seleccionado y la configuracion disponible. Falla al
     * arrancar (no en la primera request) para que un despliegue con configuracion incompleta
     * sea visible de inmediato en los logs de Cloud Run.
     */
    @jakarta.annotation.PostConstruct
    public void validateForMode() {
        if (governor == Mode.VERTEX && (vertex.getProjectId() == null || vertex.getProjectId().isBlank())) {
            throw new IllegalStateException(
                    "app.governor.mode=VERTEX requiere app.vertex.project-id");
        }
        if (reporter == Mode.HTTP && (report.getBaseUrl() == null || report.getBaseUrl().isBlank())) {
            throw new IllegalStateException(
                    "app.reporter.mode=HTTP requiere app.report.base-url");
        }
        if (store == Mode.JDBC && (jdbc.getUrl() == null || jdbc.getUrl().isBlank())) {
            throw new IllegalStateException(
                    "app.store.mode=JDBC requiere app.jdbc.url");
        }
        if (ingest == Mode.PULL && (pubsub.getSubscriptionId() == null || pubsub.getSubscriptionId().isBlank())) {
            throw new IllegalStateException(
                    "app.ingest.mode=PULL requiere app.pubsub.subscription-id");
        }
    }

    /** Vista compacta y sin secretos para los logs de arranque. */
    @Override
    public String toString() {
        return "WorkerProperties{governor=" + governor
                + ", ingest=" + ingest
                + ", store=" + store
                + ", reporter=" + reporter
                + ", vertex.model=" + vertex.getModel()
                + ", vertex.region=" + vertex.getRegion()
                + ", vertex.promptVersion=" + vertex.getPromptVersion()
                + ", vertex.timeout=" + vertex.getTimeout()
                + ", jdbc.urlConfigured=" + !jdbc.getUrl().isBlank()
                + ", report.baseUrlConfigured=" + !report.getBaseUrl().isBlank()
                + ", maxUnknownRetries=" + maxUnknownRetries
                + ", maxReportAttempts=" + maxReportAttempts
                + '}';
    }
}