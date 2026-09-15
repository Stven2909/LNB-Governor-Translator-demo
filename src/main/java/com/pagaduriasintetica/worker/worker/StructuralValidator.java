package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.SyntheticEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Valida la sintaxis mínima del evento decodificado (schema del contrato, no reglas de negocio).
 * En LNB las decisiones de negocio las toman el catálogo (pre-filtro) y el Gobernador; aquí
 * solo se evita que basura estructural (JSON malformado, campos inválidos) avance al pipeline.
 */
@Component
public class StructuralValidator {

    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})");

    public List<String> validate(SyntheticEvent event) {
        List<String> errors = new ArrayList<>();
        if (event == null) {
            errors.add("Malformed JSON event");
            return errors;
        }
        if (event.contract_version() == null || !event.contract_version().equals(PayloadHasher.CONTRACT_VERSION)) {
            errors.add("contract_version invalid");
        }
        if (blank(event.operationId())) {
            errors.add("operationId required");
        }
        if (blank(event.eventId())) {
            errors.add("eventId required");
        }
        if (blank(event.traceId())) {
            errors.add("traceId required");
        }
        if (blank(event.eventType())) {
            errors.add("eventType required");
        }
        if (blank(event.operation())) {
            errors.add("operation required");
        }
        if (blank(event.entity())) {
            errors.add("entity required");
        }
        if (event.occurredAt() == null || !ISO_DATE.matcher(event.occurredAt()).matches()) {
            errors.add("occurredAt must be ISO8601");
        }
        if (event.payload() == null) {
            errors.add("payload required");
            return errors;
        }
        if (blank(event.payload().claimId()) || event.payload().claimId().length() > 64) {
            errors.add("payload.claimId invalid");
        }
        BigDecimal amount = event.payload().amount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("payload.amount must be > 0");
        }
        if (blank(event.payload().operationDate()) || !DATE.matcher(event.payload().operationDate()).matches()) {
            errors.add("payload.operationDate must be YYYY-MM-DD");
        }
        if (blank(event.payload().beneficiary()) || event.payload().beneficiary().length() > 100) {
            errors.add("payload.beneficiary invalid");
        }
        return errors;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}