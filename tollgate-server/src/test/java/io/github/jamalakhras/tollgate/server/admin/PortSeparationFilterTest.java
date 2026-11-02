package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PortSeparationFilterTest {

    private static final int GATEWAY_PORT = 8080;
    private static final int ADMIN_PORT = 8081;

    private final PortSeparationFilter filter = new PortSeparationFilter(ADMIN_PORT);

    @ParameterizedTest(name = "{0} on port {1} passes: {2}")
    @CsvSource({
        "/admin/v1/info,      8081, true",
        "/admin,              8081, true",
        "/v3/api-docs/admin,  8081, true",
        "/v3/api-docs,        8081, true",
        "/v3/api-docs.yaml,   8081, true",
        "/v1/chat/completions,8081, false",
        "/,                   8081, false",
        "/administrator,      8081, false",
        "/admin/v1/info,      8080, false",
        "/admin,              8080, false",
        "/v3/api-docs/admin,  8080, false",
        "/v1/chat/completions,8080, true",
        "/administrator,      8080, true",
        "/v3/api-docs.yaml,   8080, false",
        "/v3/api-docsx,       8080, false",
    })
    void routesEachPathOnlyToItsPort(String path, int port, boolean passes) throws Exception {
        MockHttpServletResponse response = run(request(port, path, null));
        assertThat(response.getStatus()).isEqualTo(passes ? 200 : 404);
    }

    @ParameterizedTest(name = "servlet path {0} + path info {1}")
    @CsvSource({"/admin, /v1/info", "'', /admin/v1/info"})
    void readsThePathTheWayServletContainersSplitIt(String servletPath, String pathInfo) throws Exception {
        assertThat(run(request(GATEWAY_PORT, servletPath, pathInfo)).getStatus())
                .isEqualTo(404);
        assertThat(run(request(ADMIN_PORT, servletPath, pathInfo)).getStatus()).isEqualTo(200);
    }

    private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response;
    }

    private static MockHttpServletRequest request(int port, String servletPath, @Nullable String pathInfo) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", servletPath);
        request.setLocalPort(port);
        request.setServletPath(servletPath);
        request.setPathInfo(pathInfo);
        return request;
    }
}
