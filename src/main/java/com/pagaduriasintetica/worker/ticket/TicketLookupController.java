package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.agent.CorrelationId;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketLookupController {

    private static final Logger log = LoggerFactory.getLogger(TicketLookupController.class);

    private final TicketLookupService service;

    public TicketLookupController(TicketLookupService service) {
        this.service = service;
    }

    @PostMapping("/lookup")
    public ResponseEntity<?> lookup(
            @RequestBody TicketLookupRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        String resolvedTraceId = CorrelationId.resolve(httpRequest);
        String correlationId = StringUtils.hasText(resolvedTraceId) ? resolvedTraceId : UUID.randomUUID().toString();

        // Ecoar obligatoriamente headers de correlación distribuida
        httpResponse.setHeader("X-Correlation-Id", correlationId);
        httpResponse.setHeader("X-Request-Id", correlationId);

        if (request == null ||
                !StringUtils.hasText(request.getLotteryCode()) ||
                !StringUtils.hasText(request.getDrawNumber()) ||
                !StringUtils.hasText(request.getTicketNumber())) {

            log.warn("[correlationId={}] Consulta de boleto rechazada por datos incompletos", correlationId);
            return ResponseEntity.badRequest().body(Map.of(
                    "correlationId", correlationId,
                    "code", "INVALID_INPUT",
                    "message", "Campos obligatorios requeridos: lotteryCode, drawNumber, ticketNumber"
            ));
        }

        TicketKey key = request.toTicketKey();
        log.info("[correlationId={}] Procesando consulta de boleto: lottery={}, draw={}, ticket={}",
                correlationId, key.lotteryCode(), key.drawNumber(), key.ticketNumber());

        try {
            TicketLookupResponse response = service.lookup(key);
            log.info("[correlationId={}] Resultado para boleto {}: found={}, awarded={}, provenance={}, source={}",
                    correlationId, key.ticketNumber(), response.found(), response.awarded(), response.provenance(), response.source());
            return ResponseEntity.ok(response);
        } catch (SybaseDatabaseUnavailableException e) {
            log.error("[correlationId={}] Fallo seguro (Fail Closed): {}", correlationId, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "DATABASE_UNAVAILABLE",
                    "message", e.getMessage(),
                    "correlationId", correlationId
            ));
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(service.getPoolStatus());
    }
}
