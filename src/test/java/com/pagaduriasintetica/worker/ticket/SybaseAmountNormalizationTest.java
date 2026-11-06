package com.pagaduriasintetica.worker.ticket;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class SybaseAmountNormalizationTest {

    @ParameterizedTest(name = "normalizeAmount(\"{0}\") debe normalizar a \"{1}\" sin redondear")
    @CsvSource({
            "50.0000, 50.00",
            "50.00,   50.00",
            "50,      50.00",
            "50.5,    50.50",
            "125.75,  125.75",
            "0.50,    0.50"
    })
    @DisplayName("Formatos válidos de montos se normalizan estrictamente a 2 decimales")
    void normalizacionExitosa(String input, String expected) {
        String result = SybaseTicketRepository.normalizeAmount(input);
        assertEquals(expected, result);
    }

    @ParameterizedTest(name = "normalizeAmount(\"{0}\") debe lanzar SybaseDataIntegrityException por escala no autorizada")
    @ValueSource(strings = {
            "50.005",
            "50.0001",
            "10.123"
    })
    @DisplayName("Montos con más de 2 decimales significativos fallan cerrado sin redondear")
    void rechazaEscalasInesperadas(String input) {
        SybaseDataIntegrityException ex = assertThrows(SybaseDataIntegrityException.class,
                () -> SybaseTicketRepository.normalizeAmount(input));
        assertTrue(ex.getMessage().contains("más de 2 decimales significativos"));
    }

    @ParameterizedTest(name = "normalizeAmount(\"{0}\") debe lanzar SybaseDataIntegrityException por valor <= 0")
    @ValueSource(strings = {
            "0",
            "0.00",
            "0.0000",
            "-0.01",
            "-50.00"
    })
    @DisplayName("Montos menores o iguales a 0.00 fallan cerrado en boleto premiado")
    void rechazaMontosCeroONegativos(String input) {
        SybaseDataIntegrityException ex = assertThrows(SybaseDataIntegrityException.class,
                () -> SybaseTicketRepository.normalizeAmount(input));
        assertTrue(ex.getMessage().contains("estrictamente positivo"));
    }

    @ParameterizedTest(name = "normalizeAmount(\"{0}\") debe lanzar SybaseDataIntegrityException por nulo o vacío")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("Montos nulos o vacíos fallan cerrado")
    void rechazaMontosNulosOVacios(String input) {
        SybaseDataIntegrityException ex = assertThrows(SybaseDataIntegrityException.class,
                () -> SybaseTicketRepository.normalizeAmount(input));
        assertTrue(ex.getMessage().contains("no puede ser nulo o vacío"));
    }

    @Test
    @DisplayName("Cadenas alfanuméricas no numéricas fallan cerrado")
    void rechazaMontosNoNumericos() {
        assertThrows(SybaseDataIntegrityException.class,
                () -> SybaseTicketRepository.normalizeAmount("invalido_abc"));
    }
}
