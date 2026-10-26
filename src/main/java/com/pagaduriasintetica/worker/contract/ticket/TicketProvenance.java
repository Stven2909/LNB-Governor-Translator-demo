package com.pagaduriasintetica.worker.contract.ticket;

/**
 * Provenencia de los datos de consulta.
 */
public enum TicketProvenance {
    /** Datos de la fuente real (Sybase) */
    REAL,
    /** Datos de fixture sintético para pruebas */
    SYNTHETIC
}