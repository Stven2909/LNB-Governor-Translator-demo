package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import jakarta.servlet.http.HttpServletRequest;
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

@RestControllerAdvice
public class TicketAgentAdvice {

    private static final Logger log = LoggerFactory.getLogger(TicketAgentAdvice.class);

    // El traceId de las respuestas de error sale del MISMO request que atendio la peticion.
    // Antes se generaba un UUID nuevo, asi que un 400/502/504 perdia el id del llamador y no
    // se podia cruzar con el tramo del WS ni con la evidencia de Henry.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex,
                                                                HttpServletRequest request) {
        Map<String, Object> body = errorBody(CorrelationId.resolveOrGenerate(request), "INVALID_INPUT",
                "Entrada inválida");
        body.put("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({TicketTimeoutException.class, ResourceAccessException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex, HttpServletRequest request) {
        String traceId = CorrelationId.resolveOrGenerate(request);
        log.warn("TicketAgent WS_TIMEOUT traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(errorBody(traceId, "WS_TIMEOUT", ex.getMessage()));
    }

    @ExceptionHandler(TicketWsException.class)
    public ResponseEntity<Map<String, Object>> handleWs(TicketWsException ex, HttpServletRequest request) {
        String traceId = CorrelationId.resolveOrGenerate(request);
        log.warn("TicketAgent WS_ERROR traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(traceId, "WS_ERROR", ex.getMessage()));
    }

    @ExceptionHandler({InvalidWsResponseException.class, TicketAgentException.class})
    public ResponseEntity<Map<String, Object>> handleInvalid(Exception ex, HttpServletRequest request) {
        String traceId = CorrelationId.resolveOrGenerate(request);
        log.warn("TicketAgent INVALID_RESPONSE traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(traceId, "INVALID_RESPONSE", ex.getMessage()));
    }

    private static Map<String, Object> errorBody(String traceId, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
