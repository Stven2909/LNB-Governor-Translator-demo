package com.pagaduriasintetica.worker.contract;

// Contexto técnico del Worker que NO pertenece al contrato de Alex: el correlationId viene del
// evento (trazabilidad de negocio LNB) y el workerTraceId lo genera el Worker para sus propios
// logs/Vertex/resultado (sync_attempt.trace_id). Ambos viajan juntos en el pipeline.
public record OperationContext(
        String workerTraceId,
        String correlationId
) {
}