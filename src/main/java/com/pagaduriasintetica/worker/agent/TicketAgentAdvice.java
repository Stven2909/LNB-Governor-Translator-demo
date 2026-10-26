package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.contract.ticket.TicketKey;
import com.pagaduriasintetica.worker.contract.ticket.TicketLookupStatus;
import com.pagaduriasintetica.worker.contract.ticket.TicketProvenance;
import com.pagaduriasintetica.worker.contract.ticket.TicketQueryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestControllerAdvice
public class TicketAgentAdvice {

    private static final Logger log = LoggerFactory.getLogger(TicketAgentAdvice.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", "INVALID_INPUT");
        body.put("message", "Entrada inválida");
        body.put("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({TicketTimeoutException.class, ResourceAccessException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex) {
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", "WS_TIMEOUT");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    @ExceptionHandler(TicketWsException.class)
    public ResponseEntity<Map<String, Object>> handleWs(TicketWsException ex) {
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", "WS_ERROR");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler({InvalidWsResponseException.class, TicketAgentException.class})
    public ResponseEntity<Map<String, Object>> handleInvalid(Exception ex) {
        log.warn("TicketAgent INVALID_RESPONSE: {}", ex.getMessage());
        String traceId = UUID.randomUUID().toString();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", "INVALID_RESPONSE");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
