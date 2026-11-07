package com.pagaduriasintetica.worker.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Resuelve una sola vez la correlacion de la peticion y la deja disponible para el
 * controlador y el advice. Se ejecuta antes del despacho para que las respuestas de
 * error tempranas (400 por validacion) tambien lleven el id, incluso cuando la
 * excepcion se lanza antes de entrar al metodo del controlador.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = CorrelationId.resolveOrGenerate(request);
        request.setAttribute(CorrelationId.ATTRIBUTE, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        response.setHeader(CorrelationId.LEGACY_HEADER, correlationId);
        filterChain.doFilter(request, response);
    }
}
