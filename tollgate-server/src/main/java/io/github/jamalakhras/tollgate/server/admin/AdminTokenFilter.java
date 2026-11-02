package io.github.jamalakhras.tollgate.server.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

final class AdminTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final byte @Nullable [] expected;

    AdminTokenFilter(@Nullable String token) {
        this.expected = token == null ? null : sha256(token);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expected == null) {
            refuse(
                    response,
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "admin_disabled",
                    "Set TOLLGATE_ADMIN_TOKEN to use the admin API");
            return;
        }
        String authorization = request.getHeader("Authorization");
        String presented = authorization != null && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
                ? authorization.substring(BEARER.length()).strip()
                : "";
        if (!MessageDigest.isEqual(sha256(presented), expected)) {
            response.setHeader("WWW-Authenticate", "Bearer");
            refuse(
                    response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "admin_unauthorized",
                    "The admin token is missing or not valid");
            return;
        }
        chain.doFilter(request, response);
    }

    private static void refuse(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getOutputStream()
                .write(("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", e);
        }
    }
}
