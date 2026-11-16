package com.pagaduriasintetica.worker.agent.auth;

import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantica del ID token: cuando se pide, cuanto se cachea y como falla. El metadata server se
 * reemplaza por un obtenedor falso para que las pruebas no dependan de GCP ni de la red.
 */
class IdTokenProviderTest {

    private static final String BASE_URL = "https://caso1-ws-abc123-uc.a.run.app";
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");

    private static TicketAgentProperties props() {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setBaseUrl(BASE_URL);
        props.setAuthEnabled(true);
        return props;
    }

    private static IdTokenProvider provider(TicketAgentProperties props,
                                            IdTokenProvider.MetadataTokenFetcher fetcher) {
        return new IdTokenProvider(props, fetcher, new MutableClock(NOW));
    }

    @Test
    void deshabilitado_no_pide_token_ni_consulta_el_metadata_server() {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setBaseUrl(BASE_URL);
        AtomicInteger calls = new AtomicInteger();

        IdTokenProvider provider = provider(props, audience -> {
            calls.incrementAndGet();
            return jwt(NOW.plusSeconds(3600));
        });

        assertNull(provider.token());
        assertEquals(0, calls.get());
    }

    @Test
    void token_estatico_gana_y_no_consulta_el_metadata_server() {
        TicketAgentProperties props = props();
        props.setAuthToken("token-de-prueba");
        AtomicInteger calls = new AtomicInteger();

        IdTokenProvider provider = provider(props, audience -> {
            calls.incrementAndGet();
            return jwt(NOW.plusSeconds(3600));
        });

        assertEquals("token-de-prueba", provider.token());
        assertEquals(0, calls.get());
    }

    @Test
    void pide_el_token_una_vez_y_lo_reutiliza_mientras_no_venza() {
        AtomicInteger calls = new AtomicInteger();
        String token = jwt(NOW.plusSeconds(3600));
        IdTokenProvider provider = provider(props(), audience -> {
            calls.incrementAndGet();
            return token;
        });

        assertEquals(token, provider.token());
        assertEquals(token, provider.token());
        assertEquals(1, calls.get());
    }

    @Test
    void renueva_el_token_cuando_entra_en_la_ventana_de_vencimiento() {
        MutableClock clock = new MutableClock(NOW);
        AtomicInteger calls = new AtomicInteger();
        TicketAgentProperties props = props();
        IdTokenProvider provider = new IdTokenProvider(props, audience -> {
            int call = calls.incrementAndGet();
            // Primer token: 10 minutos; el segundo: una hora.
            return jwt(call == 1 ? NOW.plusSeconds(600) : NOW.plusSeconds(3600));
        }, clock);

        String first = provider.token();
        clock.advance(Duration.ofMinutes(8));
        String second = provider.token();
        assertEquals(first, second, "dentro de la validez no se renueva");
        assertEquals(1, calls.get());

        // Al llegar al vencimiento quedan 0s: menos que el margen de 60s, se renueva.
        clock.advance(Duration.ofMinutes(2));
        String third = provider.token();
        assertNotEquals(first, third);
        assertEquals(2, calls.get());
    }

    @Test
    void la_audiencia_por_defecto_es_la_url_base_sin_barra_final() {
        TicketAgentProperties props = props();
        props.setBaseUrl(BASE_URL + "/");
        List<String> audiences = new ArrayList<>();

        IdTokenProvider provider = provider(props, audience -> {
            audiences.add(audience);
            return jwt(NOW.plusSeconds(3600));
        });
        provider.token();

        assertEquals(List.of(BASE_URL), audiences);
    }

    @Test
    void la_audiencia_explicita_gana_sobre_la_url_base() {
        TicketAgentProperties props = props();
        props.setAuthAudience("https://otro-servicio.example/");
        List<String> audiences = new ArrayList<>();

        IdTokenProvider provider = provider(props, audience -> {
            audiences.add(audience);
            return jwt(NOW.plusSeconds(3600));
        });
        provider.token();

        assertEquals(List.of("https://otro-servicio.example"), audiences);
    }

    @Test
    void habilitada_sin_audiencia_ni_url_base_falla_al_construir() {
        TicketAgentProperties props = new TicketAgentProperties();
        props.setAuthEnabled(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider(props, audience -> jwt(NOW.plusSeconds(3600))));

        assertTrue(error.getMessage().contains("auth-audience"), error.getMessage());
    }

    @Test
    void fallo_del_metadata_server_se_reporta_como_ws_error() {
        IdTokenProvider provider = provider(props(), audience -> {
            throw new IOException("el metadata server respondio 500");
        });

        TicketWsException error = assertThrows(TicketWsException.class, provider::token);

        assertTrue(error.getMessage().contains("ID token"), error.getMessage());
        assertTrue(error.getMessage().contains("500"), error.getMessage());
    }

    @Test
    void token_sin_claim_exp_falla_cerrado() {
        IdTokenProvider provider = provider(props(), audience -> jwtSinExp());

        TicketWsException error = assertThrows(TicketWsException.class, provider::token);

        assertTrue(error.getMessage().contains("exp"), error.getMessage());
    }

    @Test
    void token_ya_vencido_falla_cerrado_y_no_se_usa() {
        IdTokenProvider provider = provider(props(), audience -> jwt(NOW.minusSeconds(10)));

        TicketWsException error = assertThrows(TicketWsException.class, provider::token);

        assertTrue(error.getMessage().contains("vencido"), error.getMessage());
    }

    @Test
    void un_fallo_no_envenena_la_cache() {
        AtomicInteger calls = new AtomicInteger();
        String token = jwt(NOW.plusSeconds(3600));
        IdTokenProvider provider = provider(props(), audience -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("metadata server no disponible");
            }
            return token;
        });

        assertThrows(TicketWsException.class, provider::token);
        assertEquals(token, provider.token());
        assertEquals(2, calls.get());
    }

    private static String jwt(Instant expiry) {
        String payload = "{\"exp\":" + expiry.getEpochSecond() + "}";
        return "eyJhbGciOiJub25lIn0." + base64Url(payload) + ".firma";
    }

    private static String jwtSinExp() {
        return "eyJhbGciOiJub25lIn0." + base64Url("{\"sub\":\"sin-exp\"}") + ".firma";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Reloj controlable: la ventana de renovacion se prueba moviendo la hora, no esperando. */
    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
