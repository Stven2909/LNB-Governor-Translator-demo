package com.pagaduriasintetica.worker.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integración física con Sybase DEV (192.168.2.14:5000).
 * Solo se ejecuta si la variable de entorno SYBASE_IT=true está presente
 * (por ejemplo, en la VM con VPN activa o entorno con enlace de red a DEV).
 * En cualquier otro entorno, JUnit la salta (SKIPPED).
 */
@EnabledIfEnvironmentVariable(named = "SYBASE_IT", matches = "true")
class SybaseTicketRealIT {

    @Test
    @DisplayName("Integración física real: Consulta boleto ganador contra Sybase DEV")
    void consultaRealContraSybaseDev() {
        SybaseTicketProperties props = new SybaseTicketProperties();
        props.setEnabled(true);
        props.setSeedEnabled(false); // Modo estricto: sin semilla
        props.setDefaultProvenance("REAL");

        SybaseTicketRepository repository = new SybaseTicketRepository(props);

        // Esperar unos segundos a que el pool intente conectar a través de la VPN
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ignored) {}

        assertTrue(repository.isPoolInitialized(), "El pool hacia Sybase DEV debe estar activo (poolActive=true)");
        assertNull(repository.getLastError(), "No debe haber errores de conexión en el pool");

        // Consultar boleto en la tabla real
        TicketLookupResponse response = repository.findTicket("LNB", "2026-10-15", "0012345678");

        assertNotNull(response);
        assertEquals("REAL", response.provenance(), "La procedencia de Sybase debe ser REAL");
        assertEquals("SYBASE", response.source(), "La fuente debe ser SYBASE");
        assertTrue(response.databaseSource().contains("Sybase ASE"), "El origen de base de datos debe ser Sybase ASE");
    }
}
