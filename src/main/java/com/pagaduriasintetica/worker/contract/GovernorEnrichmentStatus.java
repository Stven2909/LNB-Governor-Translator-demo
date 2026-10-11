package com.pagaduriasintetica.worker.contract;

/**
 * Estado del enriquecimiento de Vertex dentro del GovernorContract. El enriquecimiento es NO
 * autoritativo: se adjunta al contrato y nunca modifica la decision, el plan ni el SQL.
 *
 * - ATTACHED: Vertex respondio y el JSON cumplio el responseSchema.
 * - UNAVAILABLE: Vertex fallo o devolvio algo inutilizable; el pipeline continua con la decision
 *   deterministica y el estado degradado queda visible (motivo en GovernorEnrichment).
 * - DISABLED: no hay adaptador Vertex activo (MockGovernor de la PoC).
 */
public enum GovernorEnrichmentStatus {
    ATTACHED,
    UNAVAILABLE,
    DISABLED
}