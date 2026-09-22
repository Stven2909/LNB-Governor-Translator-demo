package com.pagaduriasintetica.worker.worker;

import com.pagaduriasintetica.worker.contract.OperationData;
import com.pagaduriasintetica.worker.contract.PaymentCommittedEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Valida la sintaxis mínima del evento PAYMENT_COMMITTED (schema del contrato de Alex, no
 * reglas de negocio). Decisiones de negocio las toman el catálogo (pre-filtro REJECTED), las
 * validationRules deterministas (invariante monetaria) y el Gobernador. Solo evita que basura
 * estructural avance: campos ausentes, eventVersion desconocida, ruteo a destino equivocado,
 * montos no positivos y datos desbordados.
 */
@Component
public class StructuralValidator {

    private static final Pattern ISO_DATE = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(Z|[+-]\\d{2}:\\d{2})");

    public List<String> validate(PaymentCommittedEvent event) {
        List<String> errors = new ArrayList<>();
        if (event == null) {
            errors.add("Malformed JSON event");
            return errors;
        }
        if (blank(event.eventId())) {
            errors.add("eventId required");
        }
        if (blank(event.eventType())) {
            errors.add("eventType required");
        }
        if (blank(event.aggregateType())) {
            errors.add("aggregateType required");
        }
        if (blank(event.aggregateId())) {
            errors.add("aggregateId required");
        }
        if (event.eventVersion() != PayloadHasher.EVENT_VERSION) {
            errors.add("eventVersion must be 1");
        }
        if (!"SYBASE".equals(event.destinationSystem())) {
            errors.add("destinationSystem must be SYBASE");
        }
        if (event.occurredAt() == null || !ISO_DATE.matcher(event.occurredAt()).matches()) {
            errors.add("occurredAt must be ISO8601");
        }
        if (blank(event.correlationId())) {
            errors.add("correlationId required");
        }
        if (blank(event.operationId())) {
            errors.add("operationId required");
        }
        if (event.operationData() == null) {
            errors.add("operationData required");
            return errors;
        }
        validateData(event.operationData(), errors);
        return errors;
    }

    private void validateData(OperationData d, List<String> errors) {
        if (blank(d.paymentId()) || d.paymentId().length() > 64) {
            errors.add("operationData.paymentId invalid");
        }
        if (blank(d.claimId()) || d.claimId().length() > 64) {
            errors.add("operationData.claimId invalid");
        }
        if (blank(d.status())) {
            errors.add("operationData.status required");
        }
        if (blank(d.paymentMethod())) {
            errors.add("operationData.paymentMethod required");
        }
        if (blank(d.currency()) || d.currency().length() > 8) {
            errors.add("operationData.currency invalid");
        }
        if (d.grossAmount() == null || d.grossAmount().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("operationData.grossAmount must be > 0");
        }
        if (d.withholdingAmount() == null || d.withholdingAmount().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("operationData.withholdingAmount must be >= 0");
        }
        if (d.netAmount() == null || d.netAmount().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add("operationData.netAmount must be > 0");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}