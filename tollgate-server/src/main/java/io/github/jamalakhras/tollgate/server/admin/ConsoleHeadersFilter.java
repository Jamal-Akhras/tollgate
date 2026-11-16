package io.github.jamalakhras.tollgate.server.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

final class ConsoleHeadersFilter extends OncePerRequestFilter {

    static final String POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; "
            + "font-src 'self'; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private static final String ASSETS = "/console/assets/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", POLICY);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader(
                "Cache-Control",
                request.getRequestURI().startsWith(ASSETS) ? "public, max-age=31536000, immutable" : "no-cache");
        chain.doFilter(request, response);
    }
}
