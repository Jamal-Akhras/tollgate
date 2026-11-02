package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AdminTokenFilterTest {

    private static final String TOKEN = "a-long-enough-admin-token-for-tests-0001";

    private static MockHttpServletResponse run(@Nullable String configured, @Nullable String authorization)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/v1/config/reload");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new AdminTokenFilter(configured).doFilter(request, response, chain);
        assertThat(chain.getRequest() != null).isEqualTo(response.getStatus() == 200);
        return response;
    }

    @Test
    void letsTheRightTokenThrough() throws Exception {
        assertThat(run(TOKEN, "Bearer " + TOKEN).getStatus()).isEqualTo(200);
        assertThat(run(TOKEN, "bearer  " + TOKEN + " ").getStatus()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer wrong", "Basic abc", "Bearer ", TOKEN})
    void refusesAnyOtherCredential(String authorization) throws Exception {
        MockHttpServletResponse response = run(TOKEN, authorization);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
        assertThat(response.getContentAsString())
                .isEqualTo("{\"code\":\"admin_unauthorized\",\"message\":\"The admin token is missing or not valid\"}");
    }

    @Test
    void refusesARequestWithNoCredential() throws Exception {
        assertThat(run(TOKEN, null).getStatus()).isEqualTo(401);
    }

    @Test
    void keepsTheAdminApiOffWithoutAToken() throws Exception {
        MockHttpServletResponse response = run(null, "Bearer " + TOKEN);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("\"code\":\"admin_disabled\"", "TOLLGATE_ADMIN_TOKEN");
    }
}
