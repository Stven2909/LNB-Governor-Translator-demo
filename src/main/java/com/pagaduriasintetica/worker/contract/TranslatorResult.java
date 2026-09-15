package com.pagaduriasintetica.worker.contract;

import java.util.List;

// DTO de salida del Traductor: el plan SQL ya validado (sql_template + 8 params + preview_sql)
// que el JDBC ejecutará contra SYNTHETIC_PAYMENTS.
public record TranslatorResult(
        String status,
        String sql_template,
        List<Object> parameters,
        String preview_sql
) {
}