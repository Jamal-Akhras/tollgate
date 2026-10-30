package io.github.jamalakhras.tollgate.server.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

final class PortSeparationFilter extends OncePerRequestFilter {

    static final String ADMIN_PATH = "/admin";
    static final String API_DOCS_PATH = "/v3/api-docs";

    private final int adminPort;

    PortSeparationFilter(int adminPort) {
        this.adminPort = adminPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean isAdminPort = request.getLocalPort() == adminPort;
        if (isAdminPort != isAdminPath(decodedPath(request))) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isAdminPath(String path) {
        return isWithin(path, ADMIN_PATH) || isWithin(path, API_DOCS_PATH);
    }

    private static boolean isWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    private static String decodedPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }
}
