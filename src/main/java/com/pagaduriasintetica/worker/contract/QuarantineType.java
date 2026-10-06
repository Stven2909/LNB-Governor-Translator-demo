package com.pagaduriasintetica.worker.contract;

/**
 * Tipo de cuarentena (Carlos, revision del Bloque 1).
 *
 * Motivo del discriminante: el nombre del estado historico es DLQ_QUARANTINED, que se confunde con
 * la Dead Letter Queue NATIVA de Pub/Sub. No son lo mismo:
 *
 * - FUNCTIONAL: el mensaje es invalido para el negocio (contrato roto, hash en colision,
 *   invariante monetaria, respuesta del Governor fuera de catalogo). ACK, sin republicar.
 * - TECHNICAL: el mensaje es valido pero el Worker no pudo procesarlo tras agotar reintentos.
 *   ACK para detener el ciclo; la traza tecnica queda para analisis.
 *
 * El nombre del estado se conserva porque forma parte del contrato observable (cuerpos de
 * respuesta HTTP y evidencia congelada); el tipo explicito evita la ambiguedad sin romperlo.
 */
public enum QuarantineType {
    FUNCTIONAL,
    TECHNICAL
}