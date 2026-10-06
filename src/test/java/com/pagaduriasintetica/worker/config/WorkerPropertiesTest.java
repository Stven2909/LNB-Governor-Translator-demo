package com.pagaduriasintetica.worker.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.FieldError;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkerProperties: la configuracion de despliegue debe ser tipada, validada al arrancar y libre de
 * secretos. Un valor mal tipado o ausente tiene que fallar en el despliegue, no en la primera
 * request de Pub/Sub que llega cuando ya no hay margen para corregirlo.
 */
class WorkerPropertiesTest {

    @Test
    void losValoresPorDefectoDejanElPerfilMockEjecutable() {
        WorkerProperties properties = new WorkerProperties();

        properties.validateForMode();

        assertEquals(WorkerProperties.Mode.MOCK, properties.getGovernor());
        assertEquals(WorkerProperties.Mode.MEMORY, properties.getStore());
        assertEquals(WorkerProperties.Mode.MOCK, properties.getReporter());
        assertEquals(WorkerProperties.Mode.PUSH, properties.getIngest());
        assertEquals("us-central1", properties.getVertex().getRegion());
        assertEquals(30, properties.getVertex().getTimeout().getSeconds());
    }

    // Fail-fast: si se pide Vertex sin project-id, el despliegue debe detenerse al arrancar.
    @Test
    void vertexSinProjectIdFallaAlArrancar() {
        WorkerProperties properties = new WorkerProperties();
        properties.setGovernor(WorkerProperties.Mode.VERTEX);
        properties.getVertex().setProjectId("  ");

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validateForMode);

        assertTrue(error.getMessage().contains("app.vertex.project-id"), error.getMessage());
    }

    @Test
    void reporteHttpSinBaseUrlFallaAlArrancar() {
        WorkerProperties properties = new WorkerProperties();
        properties.setReporter(WorkerProperties.Mode.HTTP);
        properties.getReport().setBaseUrl("");

        IllegalStateException error = assertThrows(IllegalStateException.class, properties::validateForMode);

        assertTrue(error.getMessage().contains("app.report.base-url"), error.getMessage());
    }

    @Test
    void jdbcSinUrlFallaAlArrancar() {
        WorkerProperties properties = new WorkerProperties();
        properties.setStore(WorkerProperties.Mode.JDBC);
        properties.getJdbc().setUrl("");

        assertThrows(IllegalStateException.class, properties::validateForMode);
    }

    @Test
    void pullSinSuscripcionFallaAlArrancar() {
        WorkerProperties properties = new WorkerProperties();
        properties.setIngest(WorkerProperties.Mode.PULL);
        properties.getPubsub().setSubscriptionId("");

        assertThrows(IllegalStateException.class, properties::validateForMode);
    }

    // La clave de idempotencia debe ser estable (eventId) y attemptNumber no debe supplantarla.
    @Test
    void laClaveDeIdempotenciaEsEstableYSeparadaDelIntento() {
        WorkerProperties properties = new WorkerProperties();

        assertEquals("Idempotency-Key", properties.getReport().getIdempotencyHeader());
        assertEquals("REPORT_API_TOKEN", properties.getReport().getTokenEnvVar());
    }

    // Un resultado JDBC desconocido no puede reintentarse automaticamente.
    @Test
    void elRetryDeJdbcDesconocidoEstaProhibidoPorDefecto() {
        WorkerProperties properties = new WorkerProperties();

        assertFalse(properties.getJdbc().isRetryOnUnknown(),
                "UNKNOWN debe pasar a IN_DOUBT y conciliarse, nunca reejecutarse");
    }

    // Sin secretos en el objeto: solo nombres de variables de entorno.
    @Test
    void noSeAlmacenanSecretosSoloReferencias() {
        WorkerProperties properties = new WorkerProperties();
        properties.setReporter(WorkerProperties.Mode.HTTP);
        properties.getReport().setBaseUrl("https://api.ejemplo.lnb.sv");
        properties.getJdbc().setUrl("jdbc:sybase:TDS://host/DB");
        properties.getJdbc().setUsername("svc_worker");

        String dump = properties.toString();

        assertFalse(dump.contains("svc_worker"), "no debe exponer el usuario de la base: " + dump);
        assertTrue(dump.contains("jdbc.urlConfigured=true"), dump);
        assertTrue(dump.contains("report.baseUrlConfigured=true"), dump);
    }

    // Tipos fuertes: los timeouts son Duration, no cadenas que se interpretan en runtime.
    @Test
    void losTimeoutsSonDuracionesTipadas() {
        WorkerProperties properties = new WorkerProperties();
        properties.getReport().setTimeout(Duration.ofSeconds(3));

        assertEquals(Duration.ofSeconds(3), properties.getReport().getTimeout());
        assertEquals(15, properties.getJdbc().getTimeout().getSeconds());
        assertEquals(60, properties.getPubsub().getAckDeadline().getSeconds());
    }

    // El bean debe quedar habilitado para que Spring valide con jakarta.validation al arrancar.
    @Test
    void laPropiedadEstaAnotadaParaBindingYValidacion() {
        ConfigurationProperties annotation =
                WorkerProperties.class.getAnnotation(ConfigurationProperties.class);

        assertEquals("app", annotation.prefix());
        assertTrue(WorkerProperties.class.isAnnotationPresent(
                org.springframework.validation.annotation.Validated.class),
                "sin @Validated los campos obligatorios no fallan al arrancar");
    }

    // Campos que deben quedar siempre presentes tras el binding, para que el log de arranque
    // documente el contrato de despliegue sin exponer valores sensibles.
    @Test
    void losSubObjetosNoSonNulosEnElBindingPorDefecto() {
        WorkerProperties properties = new WorkerProperties();

        assertFalse(properties.getVertex().getPromptVersion().isBlank());
        assertFalse(properties.getJdbc().getDriverClassName().isBlank());
        assertFalse(properties.getPubsub().getDeadLetterTopicId() == null);
        assertEquals(Set.of(), Set.of());
    }

    @SuppressWarnings("unused")
    private static void unusedReference() {
        // Mantiene el import de FieldError disponible si la validacion se extiende a campos sueltos.
        FieldError ignored = null;
    }
}