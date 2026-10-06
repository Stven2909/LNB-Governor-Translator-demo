package com.pagaduriasintetica.worker.contract;

/**
 * Dimension del reporte, SEPARADA del estado del pipeline (Carlos, revision del Bloque 1).
 *
 * Razon: el estado del pipeline describe hasta donde llego la operacion, mientras que el reporte es
 * un efecto lateral que ocurre DESPUES de que Sybase confirma. Si un fallo del reporte se
 * codificara en el estado del pipeline (RETRYABLE, BLOCKED_CONFIGURATION, QUARANTINE_TECHNICAL),
 * se perderia la verdad economica: que el pago ya ocurrio. Con esta dimension, un fallo de
 * reporte queda como reportStatus=BLOCKED + manualActionRequired=true sobre un estado que sigue
 * diciendo JDBC_COMMITTED.
 *
 * - NOT_APPLICABLE: la operacion no llego a la etapa de reporte (rechazo, cuarentena, error previo).
 * - PENDING:        el commit se confirmo y el reporte esta en curso o pendiente de reintento.
 * - SUCCEEDED:      el reporte fue aceptado.
 * - BLOCKED:        el reporte no pudo completarse y requiere intervencion manual; el commit NO
 *                   se revierte ni se reintenta a ciegas.
 */
public enum ReportStatus {
    NOT_APPLICABLE,
    PENDING,
    SUCCEEDED,
    BLOCKED
}