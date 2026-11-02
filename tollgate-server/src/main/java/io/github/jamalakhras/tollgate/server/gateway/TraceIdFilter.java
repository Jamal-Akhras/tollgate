package io.github.jamalakhras.tollgate.server.gateway;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

final class TraceIdFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Tollgate-Trace-Id";
    static final String LOG_KEY = "traceId";
    static final String ATTRIBUTE = "tollgate.traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        response.setHeader("X-Content-Type-Options", "nosniff");
        MDC.put(LOG_KEY, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(LOG_KEY);
        }
    }
}
