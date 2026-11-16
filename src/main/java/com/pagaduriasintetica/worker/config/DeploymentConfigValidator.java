package com.pagaduriasintetica.worker.config;

import com.pagaduriasintetica.worker.agent.tool.TicketAgentProperties;
import com.pagaduriasintetica.worker.ticket.SybaseTicketProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Verifica la configuracion de despliegue al arrancar y falla de inmediato si es incompleta. Con
 * Spring Boot, un puerto o una sonda mal configurados no impiden el arranque: el servicio levanta
 * y falla en la primera request de Pub/Sub, que es cuando ya no hay margen para corregirlo.
 * Este runner convierte eso en un fallo de despliegue visible en los logs de Cloud Run.
 *
 * <p>Ademas de la coherencia de modos del Worker, valida la coherencia del Caso 1 (rol WS y
 * procedencia del agente). Son reglas de arranque, no de request: un despliegue que no puede
 * responder nunca debe fallar en el deploy, no en la primera consulta.</p>
 *
 * No registra secretos: WorkerProperties.toString() solo expone nombres y banderas de presencia.
 */
@Component
public class DeploymentConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeploymentConfigValidator.class);

    /** Perfil que representa el rol WS (contrato de boletos de Carlos). */
    static final String WS_PROFILE = "carlos";

    private final WorkerProperties properties;
    private final SybaseTicketProperties sybaseTicket;
    private final TicketAgentProperties ticketAgent;
    private final Environment environment;

    public DeploymentConfigValidator(WorkerProperties properties,
                                     SybaseTicketProperties sybaseTicket,
                                     TicketAgentProperties ticketAgent,
                                     Environment environment) {
        this.properties = properties;
        this.sybaseTicket = sybaseTicket;
        this.ticketAgent = ticketAgent;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        properties.validateForMode();
        validateTicketSource();
        validateSybaseTicketTable();
        validateTicketProvenance();
        log.info("Configuracion de despliegue validada: {}", properties);
    }

    /**
     * Un servicio en rol WS necesita una fuente de datos declarada. Con el pool apagado y la
     * semilla apagada el servicio arranca, responde {@code /status} y falla en toda consulta: es
     * el peor modo de fallo porque el health check queda en verde y el error aparece recien en la
     * demostracion. Se valida solo en el rol WS: el servicio del agente tambien corre con ambas
     * banderas en false porque no consulta boletos, solo delega en el WS.
     */
    void validateTicketSource() {
        if (!environment.acceptsProfiles(Profiles.of(WS_PROFILE))) {
            return;
        }
        if (!sybaseTicket.isEnabled() && !sybaseTicket.isSeedEnabled()) {
            throw new IllegalStateException(
                    "Rol WS (perfil " + WS_PROFILE + ") sin fuente de datos: activar "
                            + "app.sybase.ticket.enabled (Sybase real) o "
                            + "app.sybase.ticket.seed-enabled (semilla autorizada). "
                            + "Con ambas en false el WS arranca pero toda consulta falla.");
        }
    }

    /**
     * Con app.sybase.ticket.enabled=true el WS ejecuta JDBC y arma el FROM con
     * app.sybase.ticket.table-name. Si viene vacio el SQL no es valido y el error aparece en la
     * primera consulta, con el health check en verde. La tabla real la confirma IT LNB: no hay
     * default, para no sugerir una tabla que nadie verifico.
     *
     * <p>No mira el perfil: tambien falla si alguien arranca el agente con el pool encendido y sin
     * tabla, que es una configuracion que no puede responder.</p>
     */
    void validateSybaseTicketTable() {
        if (sybaseTicket.isEnabled() && normalize(sybaseTicket.getTableName()).isEmpty()) {
            throw new IllegalStateException(
                    "app.sybase.ticket.enabled=true exige app.sybase.ticket.table-name: "
                            + "definir la tabla/vista autorizada de Sybase confirmada por IT LNB.");
        }
    }

    /**
     * Espejo en configuracion de la regla que {@code TicketAgent} aplica sobre cada respuesta del
     * WS: {@code REAL} exige {@code SYBASE} y {@code SYBASE} exige {@code REAL}. En runtime esa
     * incoherencia se rechaza por request; declararla en el despliegue solo produce un servicio que
     * no puede responder nunca, asi que se corta al arrancar.
     */
    void validateTicketProvenance() {
        String provenance = normalize(ticketAgent.getProvenance());
        String source = normalize(ticketAgent.getSource());

        if ("REAL".equals(provenance) && !"SYBASE".equals(source)) {
            throw new IllegalStateException(
                    "app.ticket-agent.provenance=REAL exige app.ticket-agent.source=SYBASE: "
                            + "un dato REAL no se declara por configuracion sin origen Sybase.");
        }
        if ("SYBASE".equals(source) && !"REAL".equals(provenance)) {
            throw new IllegalStateException(
                    "app.ticket-agent.source=SYBASE exige app.ticket-agent.provenance=REAL: "
                            + "el origen Sybase no se declara sobre un dato sintetico.");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
