package com.pagaduriasintetica.worker.config;

import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import com.pagaduriasintetica.worker.ticket.SybaseTicketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reglas de arranque del Caso 1. Cada caso reproduce una configuracion de despliegue real: el rol
 * WS (perfil {@code carlos}) y el rol agente, que corre sin ese perfil.
 */
class DeploymentConfigValidatorTest {

    private final WorkerProperties worker = new WorkerProperties();
    private final SybaseTicketProperties sybase = new SybaseTicketProperties();
    private final TicketAgentProperties agent = new TicketAgentProperties();
    private final MockEnvironment environment = new MockEnvironment();

    private void run() {
        new DeploymentConfigValidator(worker, sybase, agent, environment)
                .run(new DefaultApplicationArguments(new String[0]));
    }

    @Test
    void rolWsSinPoolNiSemillaFallaAlArrancar() {
        environment.setActiveProfiles(DeploymentConfigValidator.WS_PROFILE);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::run);
        assertTrue(error.getMessage().contains("sin fuente de datos"), error.getMessage());
    }

    @Test
    void rolWsConSemillaArranca() {
        environment.setActiveProfiles(DeploymentConfigValidator.WS_PROFILE);
        sybase.setSeedEnabled(true);

        assertDoesNotThrow(this::run);
    }

    @Test
    void rolWsConPoolSybaseArranca() {
        environment.setActiveProfiles(DeploymentConfigValidator.WS_PROFILE);
        sybase.setEnabled(true);
        sybase.setTableName("TICKETS_DEV");

        assertDoesNotThrow(this::run);
    }

    @Test
    void poolSybaseSinTablaFallaAlArrancar() {
        environment.setActiveProfiles(DeploymentConfigValidator.WS_PROFILE);
        sybase.setEnabled(true);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::run);
        assertTrue(error.getMessage().contains("table-name"), error.getMessage());
    }

    @Test
    void poolSybaseSinTablaFallaTambienSinPerfilWs() {
        // La regla no depende del perfil: un agente con el pool encendido y sin tabla tampoco
        // puede responder, y el error debe aparecer en el deploy y no en la primera consulta.
        sybase.setEnabled(true);

        IllegalStateException error = assertThrows(IllegalStateException.class, this::run);
        assertTrue(error.getMessage().contains("table-name"), error.getMessage());
    }

    @Test
    void servicioDelAgenteArrancaSinPoolNiSemilla() {
        // Sin el perfil carlos: el agente no consulta boletos, delega en el WS.
        assertDoesNotThrow(this::run);
    }

    @Test
    void provenanceRealSinSourceSybaseFallaAlArrancar() {
        agent.setProvenance("REAL");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::run);
        assertTrue(error.getMessage().contains("source=SYBASE"), error.getMessage());
    }

    @Test
    void sourceSybaseConProvenanceSinteticaFallaAlArrancar() {
        agent.setSource("SYBASE");

        IllegalStateException error = assertThrows(IllegalStateException.class, this::run);
        assertTrue(error.getMessage().contains("provenance=REAL"), error.getMessage());
    }

    @Test
    void procedenciaCoherenteArranca() {
        agent.setProvenance("REAL");
        agent.setSource("SYBASE");

        assertDoesNotThrow(this::run);
    }

    @Test
    void configuracionPorDefectoArranca() {
        assertDoesNotThrow(this::run);
    }
}
