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
 * (por ejemplo, en la VM con VPN activa y conectividad de red a DEV).
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
    void consultaRealContraSybaseDev() throws InterruptedException {
        // 1. Cargar obligatoriamente credenciales y URL del entorno
        String sybaseJdbcUrl = System.getenv("APP_SYBASE_TICKET_URL");
        String sybaseUsername = System.getenv("APP_SYBASE_TICKET_USERNAME");
        String sybasePassword = System.getenv("APP_SYBASE_TICKET_PASSWORD");

        assertNotNull(sybaseJdbcUrl, "Variable de entorno APP_SYBASE_TICKET_URL es obligatoria para SYBASE_IT=true");
        assertFalse(sybaseJdbcUrl.isBlank(), "APP_SYBASE_TICKET_URL no puede estar vacía");
        assertNotNull(sybaseUsername, "Variable de entorno APP_SYBASE_TICKET_USERNAME es obligatoria para SYBASE_IT=true");
        assertFalse(sybaseUsername.isBlank(), "APP_SYBASE_TICKET_USERNAME no puede estar vacía");

        SybaseTicketProperties props = new SybaseTicketProperties();
        props.setEnabled(true);
        props.setSeedEnabled(false); // Modo estricto real: sin semilla
        props.setDefaultProvenance("REAL");
        props.setUrl(sybaseJdbcUrl);
        props.setUsername(sybaseUsername);
        props.setPassword(sybasePassword != null ? sybasePassword : "");

        repository = new SybaseTicketRepository(props);

        // 2. Esperar que el pool HikariCP inicialice (hasta deadline de 10s con pausas de 100ms)
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (!repository.isPoolInitialized() && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }

        assertTrue(repository.isPoolInitialized(),
                "El pool hacia Sybase DEV no inicializó en el tiempo esperado. Error: " + repository.getPoolError());
        assertNull(repository.getPoolError(), "No debe haber errores de conexión en el pool");

        // 3. Exigir las variables del boleto real confirmado (sin defaults inventados)
        String expectedTicket = System.getenv("SYBASE_REAL_TICKET");
        String drawNumber = System.getenv("SYBASE_REAL_DRAW");
        String lotteryCode = System.getenv("SYBASE_REAL_LOTTERY");

        assertNotNull(expectedTicket, "Debe configurarse la variable SYBASE_REAL_TICKET con el número de boleto real confirmado");
        assertFalse(expectedTicket.isBlank(), "SYBASE_REAL_TICKET no puede estar vacía");
        assertNotNull(drawNumber, "Debe configurarse la variable SYBASE_REAL_DRAW con el drawNumber real confirmado");
        assertFalse(drawNumber.isBlank(), "SYBASE_REAL_DRAW no puede estar vacía");
        assertNotNull(lotteryCode, "Debe configurarse la variable SYBASE_REAL_LOTTERY con el lotteryCode real confirmado");
        assertFalse(lotteryCode.isBlank(), "SYBASE_REAL_LOTTERY no puede estar vacía");

        String expectedAmount = System.getenv().getOrDefault("SYBASE_REAL_AMOUNT", "50.00");
        String expectedCurrency = System.getenv().getOrDefault("SYBASE_REAL_CURRENCY", "USD");
        String expectedStatus = System.getenv("SYBASE_REAL_STATUS");

        // 4. Consultar boleto en la tabla real de Sybase ASE
        TicketLookupResponse response = repository.findTicket(lotteryCode, drawNumber, expectedTicket);

        // 5. Aserciones estrictas para el Caso 1
        assertNotNull(response, "La respuesta no debe ser nula");
        assertTrue(response.found(), "El boleto real debe existir en la base de datos");
        assertTrue(response.awarded(), "El boleto real consultado debe estar premiado");
        assertEquals(expectedTicket, response.ticketNumber(), "El ticketNumber debe coincidir exactamente con el consultado");
        assertNotNull(response.prize(), "El premio debe estar presente para un boleto premiado");
        assertEquals(expectedAmount, response.prize().amount(), "El monto del premio debe coincidir con el esperado");
        assertEquals(expectedCurrency, response.prize().currency(), "La moneda del premio debe coincidir con la esperada");
        assertEquals("CASH", response.prize().category(), "La categoría debe ser CASH");

        if (expectedStatus != null && !expectedStatus.isBlank()) {
            assertEquals(expectedStatus, response.collectionStatus(), "collectionStatus debe coincidir con el esperado");
        } else {
            assertNotNull(response.collectionStatus(), "collectionStatus no debe ser nulo en la fila real de la base de datos");
        }

        assertEquals("REAL", response.provenance(), "La procedencia de Sybase debe ser REAL");
        assertEquals("SYBASE", response.source(), "La fuente debe ser SYBASE");
        assertEquals("Sybase ASE LNB DEV", response.databaseSource(), "El origen de base de datos debe ser 'Sybase ASE LNB DEV'");
        assertNull(repository.getLastQueryError(), "No debe haber errores de consulta");
    }
}
