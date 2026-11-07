package com.pagaduriasintetica.worker.agent;

import com.pagaduriasintetica.worker.agent.exception.InvalidWsResponseException;
import com.pagaduriasintetica.worker.agent.exception.TicketAgentException;
import com.pagaduriasintetica.worker.agent.exception.TicketDataIntegrityException;
import com.pagaduriasintetica.worker.agent.exception.TicketDatabaseUnavailableException;
import com.pagaduriasintetica.worker.agent.exception.TicketTimeoutException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsException;
import com.pagaduriasintetica.worker.agent.exception.TicketWsUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
                                                                HttpServletRequest request,
                                                                HttpServletResponse response) {
        String traceId = correlation(request, response);
        Map<String, Object> body = errorBody(traceId, "INVALID_INPUT", "Entrada inválida");
        body.put("errors", ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage()).toList());
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler({TicketTimeoutException.class, ResourceAccessException.class})
    public ResponseEntity<Map<String, Object>> handleTimeout(Exception ex,
                                                             HttpServletRequest request,
                                                             HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent WS_TIMEOUT traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(errorBody(traceId, "WS_TIMEOUT", ex.getMessage()));
    }

    /** 503 del WS: la base de datos no esta disponible. Se preserva, no se degrada a 502. */
    @ExceptionHandler(TicketDatabaseUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleDatabaseUnavailable(TicketDatabaseUnavailableException ex,
                                                                         HttpServletRequest request,
                                                                         HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent DATABASE_UNAVAILABLE traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody(traceId, "DATABASE_UNAVAILABLE", ex.getMessage()));
    }

    /** 502 del WS: el dato existe pero es inconsistente. */
    @ExceptionHandler(TicketDataIntegrityException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(TicketDataIntegrityException ex,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent DATA_INTEGRITY_ERROR traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(traceId, "DATA_INTEGRITY_ERROR", ex.getMessage()));
    }

    /**
     * 503 del WS sin codigo DATABASE_UNAVAILABLE: se conserva el 503 porque el
     * upstream no esta disponible, pero no se afirma la causa que el WS no declaro.
     */
    @ExceptionHandler(TicketWsUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleWsUnavailable(TicketWsUnavailableException ex,
                                                                   HttpServletRequest request,
                                                                   HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent WS_UNAVAILABLE traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(errorBody(traceId, "WS_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(TicketWsException.class)
    public ResponseEntity<Map<String, Object>> handleWs(TicketWsException ex,
                                                        HttpServletRequest request,
                                                        HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent WS_ERROR traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(traceId, "WS_ERROR", ex.getMessage()));
    }

    @ExceptionHandler({InvalidWsResponseException.class, TicketAgentException.class})
    public ResponseEntity<Map<String, Object>> handleInvalid(Exception ex,
                                                             HttpServletRequest request,
                                                             HttpServletResponse response) {
        String traceId = correlation(request, response);
        log.warn("TicketAgent INVALID_RESPONSE traceId={}: {}", traceId, ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(traceId, "INVALID_RESPONSE", ex.getMessage()));
    }

    /**
     * Resuelve la correlacion y la escribe en las cabeceras de error. Con el filtro activo
     * ambos valores son el mismo id ya resuelto; sin filtro, se genera aqui y aun asi se ecoa.
     */
    private static String correlation(HttpServletRequest request, HttpServletResponse response) {
        String traceId = CorrelationId.currentOrGenerate(request);
        if (response != null) {
            response.setHeader(CorrelationId.HEADER, traceId);
            response.setHeader(CorrelationId.LEGACY_HEADER, traceId);
        }
        return traceId;
    }

    private static Map<String, Object> errorBody(String traceId, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", traceId);
        body.put("code", code);
        body.put("message", message);
        return body;
    }
}
