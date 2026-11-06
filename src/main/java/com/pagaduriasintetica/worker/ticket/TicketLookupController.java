package com.pagaduriasintetica.worker.ticket;

import com.pagaduriasintetica.worker.agent.CorrelationId;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            HttpServletRequest httpRequest) {

        String resolvedTraceId = CorrelationId.resolve(httpRequest);
        String traceId = StringUtils.hasText(resolvedTraceId) ? resolvedTraceId : UUID.randomUUID().toString();

        if (request == null ||
                !StringUtils.hasText(request.getLotteryCode()) ||
                !StringUtils.hasText(request.getDrawNumber()) ||
                !StringUtils.hasText(request.getTicketNumber())) {

            log.warn("[traceId={}] Consulta de boleto rechazada por datos incompletos", traceId);
            return ResponseEntity.badRequest().body(Map.of(
                    "traceId", traceId,
                    "code", "INVALID_INPUT",
                    "message", "Campos obligatorios requeridos: lotteryCode, drawNumber, ticketNumber"
            ));
        }

        TicketKey key = request.toTicketKey();
        log.info("[traceId={}] Procesando consulta de boleto: lottery={}, draw={}, ticket={}",
                traceId, key.lotteryCode(), key.drawNumber(), key.ticketNumber());

        TicketLookupResponse response = service.lookup(key);
        log.info("[traceId={}] Resultado para boleto {}: found={}, awarded={}, provenance={}",
                traceId, key.ticketNumber(), response.found(), response.awarded(), response.provenance());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(service.getPoolStatus());
    }
}
