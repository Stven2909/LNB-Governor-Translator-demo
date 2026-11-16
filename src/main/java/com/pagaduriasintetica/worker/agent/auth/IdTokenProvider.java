package com.pagaduriasintetica.worker.agent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Provee el ID token que Cloud Run exige cuando el WS esta desplegado como privado
 * ({@code --no-allow-unauthenticated}). Sin token, Cloud Run rechaza la peticion con 403 antes de
 * ejecutar codigo del WS, asi que el agente no puede distinguir "sin permiso" de "sin boleto":
 * por eso el fallo aqui es cerrado y se reporta como {@link TicketWsException}.
 *
 * <p>Fuentes del token, en orden:</p>
 * <ol>
 *   <li>Valor estatico de {@code app.ticket-agent.auth-token}: solo para pruebas manuales contra
 *       un WS privado desde fuera de GCP. No se cachea ni se renueva.</li>
 *   <li>Metadata server de la instancia ({@code metadata.google.internal}): es el camino normal en
 *       Cloud Run. El token se cachea hasta {@code exp} menos un margen de 60 s, de modo que no se
 *       pide uno nuevo en cada consulta pero nunca se usa uno a punto de vencer.</li>
 * </ol>
 *
 * <p>La audiencia debe ser la URL del propio servicio, no el dominio que lo publique: Cloud Run
 * compara la audiencia del token contra su URL y responde 401 si no coincide.</p>
 *
 * <p>Ningun mensaje de error incluye el token: un token en un log es una credencial filtrada.</p>
 */
@Component
public class IdTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(IdTokenProvider.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private static final String METADATA_IDENTITY_URL =
            "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/identity";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    /** No se usa un token que expire en menos de este margen. */
    private static final Duration RENEWAL_SKEW = Duration.ofSeconds(60);

    private final boolean enabled;
    private final String audience;
    private final String staticToken;
    private final MetadataTokenFetcher fetcher;
    private final Clock clock;

    private final Object refreshLock = new Object();
    private volatile String cachedToken;
    private volatile Instant cachedExpiry = Instant.EPOCH;

    @Autowired
    public IdTokenProvider(TicketAgentProperties props) {
        this(props, IdTokenProvider::fetchFromMetadataServer, Clock.systemUTC());
    }

    /**
     * Constructor para pruebas: permite inyectar el obtenedor de tokens y el reloj en lugar de
     * depender del metadata server y de la hora real.
     */
    IdTokenProvider(TicketAgentProperties props, MetadataTokenFetcher fetcher, Clock clock) {
        this.enabled = props.isAuthEnabled();
        this.staticToken = StringUtils.hasText(props.getAuthToken())
                ? props.getAuthToken().trim()
                : null;
        this.fetcher = fetcher;
        this.clock = clock;
        this.audience = this.enabled ? resolveAudience(props) : null;
    }

    /**
     * Token para la siguiente consulta al WS, o {@code null} cuando {@code auth-enabled=false}.
     *
     * @throws TicketWsException si la auth esta activa y no se pudo obtener un token utilizable.
     *     No hay reintento silencioso: la consulta falla y el agente responde 502 WS_ERROR en vez
     *     de viajar sin credencial y recibir un 403 que podria confundirse con otro error.
     */
    public String token() {
        if (!enabled) {
            return null;
        }
        if (staticToken != null) {
            return staticToken;
        }

        Instant now = clock.instant();
        String current = cachedToken;
        if (current != null && now.isBefore(cachedExpiry.minus(RENEWAL_SKEW))) {
            return current;
        }

        synchronized (refreshLock) {
            now = clock.instant();
            if (cachedToken != null && now.isBefore(cachedExpiry.minus(RENEWAL_SKEW))) {
                return cachedToken;
            }

            String fresh;
            Instant expiry;
            try {
                fresh = fetcher.fetch(audience);
                if (!StringUtils.hasText(fresh)) {
                    throw new IOException("respuesta vacia del metadata server");
                }
                fresh = fresh.trim();
                expiry = expiryOf(fresh);
                if (!expiry.isAfter(now)) {
                    throw new IOException("el metadata server devolvio un token ya vencido");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TicketWsException("Interrumpido al obtener el ID token del WS", e);
            } catch (IOException | RuntimeException e) {
                throw new TicketWsException(
                        "No se pudo obtener el ID token para el WS: " + e.getMessage(), e);
            }

            cachedToken = fresh;
            cachedExpiry = expiry;
            log.debug("ID token del WS renovado; expira en {}", expiry);
            return fresh;
        }
    }

    /** Audiencia efectiva: la configurada o, si no hay, la URL base sin barra final. */
    private static String resolveAudience(TicketAgentProperties props) {
        String audience = StringUtils.hasText(props.getAuthAudience())
                ? props.getAuthAudience().trim()
                : (props.getBaseUrl() == null ? "" : props.getBaseUrl().trim());
        while (audience.endsWith("/")) {
            audience = audience.substring(0, audience.length() - 1);
        }
        if (!StringUtils.hasText(audience)) {
            throw new IllegalStateException(
                    "app.ticket-agent.auth-enabled=true exige app.ticket-agent.auth-audience "
                            + "o app.ticket-agent.base-url: la audiencia debe ser la URL del WS");
        }
        return audience;
    }

    /**
     * Pide un ID token al metadata server. Se usa {@code format=full} para recibir el JWT con el
     * claim {@code exp}: el token completo es el que espera Cloud Run como bearer.
     */
    static String fetchFromMetadataServer(String audience) throws IOException, InterruptedException {
        URI uri = URI.create(METADATA_IDENTITY_URL
                + "?audience=" + URLEncoder.encode(audience, StandardCharsets.UTF_8)
                + "&format=full");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Metadata-Flavor", "Google")
                .GET()
                .build();

        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("el metadata server respondio " + response.statusCode());
        }
        return response.body();
    }

    /** Lee {@code exp} del payload del JWT. Sin {@code exp} no hay forma de cachear con seguridad. */
    private static Instant expiryOf(String jwt) throws IOException {
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            throw new IOException("el ID token no tiene formato JWT");
        }

        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IOException("el payload del ID token no es base64url", e);
        }

        JsonNode exp;
        try {
            exp = JSON.readTree(payload).get("exp");
        } catch (IOException e) {
            throw new IOException("el payload del ID token no es JSON", e);
        }
        if (exp == null || !exp.canConvertToLong()) {
            throw new IOException("el ID token no declara exp");
        }
        return Instant.ofEpochSecond(exp.asLong());
    }

    /** Punto de inyeccion del obtenedor de tokens para las pruebas. */
    @FunctionalInterface
    interface MetadataTokenFetcher {
        String fetch(String audience) throws IOException, InterruptedException;
    }
}
