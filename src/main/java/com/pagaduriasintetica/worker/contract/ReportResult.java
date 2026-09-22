package com.pagaduriasintetica.worker.contract;

// Respuesta del ResultReporter: outcome + detalle legible.
public record ReportResult(
        ReportOutcome outcome,
        String detail
) {
    public static ReportResult ok(String detail) {
        return new ReportResult(ReportOutcome.SUCCEEDED, detail);
    }

    public static ReportResult conflict(String detail) {
        return new ReportResult(ReportOutcome.CONFLICT, detail);
    }

    public static ReportResult failed(String detail) {
        return new ReportResult(ReportOutcome.FAILED, detail);
    }
}