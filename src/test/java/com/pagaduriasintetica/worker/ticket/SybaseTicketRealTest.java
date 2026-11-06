package com.pagaduriasintetica.worker.ticket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prueba de integración física con Sybase DEV.
 * Solo se ejecuta si la variable de entorno SYBASE_IT=true está presente
 * (por ejemplo, en la VM con VPN activa o entorno con enlace de red a DEV).
 * En cualquier otro entorno, JUnit la salta (SKIPPED).
 */
@EnabledIfEnvironmentVariable(named = "SYBASE_IT", matches = "true")
class SybaseTicketRealTest {

    private SybaseTicketRepository repository;

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    @Test
    @DisplayName("Integración física real: Consulta boleto ganador contra Sybase DEV")
    void consultaRealContraSybaseDev() {
        SybaseTicketProperties props = new SybaseTicketProperties();
        props.setEnabled(true);
        props.setSeedEnabled(false); // Modo estricto: sin semilla
        props.setDefaultProvenance("REAL");

        repository = new SybaseTicketRepository(props);

        // Esperar activamente hasta que el pool inicialice o se agote el timeout (sin Thread.sleep fijo)
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!repository.isPoolInitialized() && repository.getLastError() == null && Instant.now().isBefore(deadline)) {
            Thread.onSpinWait();
        }

        assertTrue(repository.isPoolInitialized(), "El pool hacia Sybase DEV debe estar activo (poolActive=true). Error: " + repository.getLastError());
        assertNull(repository.getLastError(), "No debe haber errores de conexión en el pool");

        // Boleto real confirmado por Alex/Matthew (o variable de entorno SYBASE_REAL_TICKET)
        String expectedTicket = System.getenv().getOrDefault("SYBASE_REAL_TICKET", "04521");
        String drawNumber = System.getenv().getOrDefault("SYBASE_REAL_DRAW", "2026-10-15");
        String lotteryCode = System.getenv().getOrDefault("SYBASE_REAL_LOTTERY", "LNB");

        // Consultar boleto en la tabla real de Sybase
        TicketLookupResponse response = repository.findTicket(lotteryCode, drawNumber, expectedTicket);

        assertNotNull(response, "La respuesta no debe ser nula");
        assertTrue(response.found(), "El boleto real debe existir en la base de datos");
        assertTrue(response.awarded(), "El boleto real consultado debe estar premiado");
        assertEquals(expectedTicket, response.ticketNumber(), "El número de boleto debe coincidir con el consultado");
        assertNotNull(response.prize(), "El premio debe estar presente para un boleto premiado");
        assertNotNull(response.prize().amount(), "El monto del premio debe ser devuelto normalizado");
        assertNotNull(response.collectionStatus(), "collectionStatus debe reflejar el valor de la fila de base de datos");
        assertEquals("REAL", response.provenance(), "La procedencia de Sybase debe ser REAL");
        assertEquals("SYBASE", response.source(), "La fuente debe ser SYBASE");
        assertEquals("Sybase ASE LNB DEV", response.databaseSource(), "El origen de base de datos debe ser 'Sybase ASE LNB DEV'");
    }
}
