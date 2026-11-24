package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.testsupport.TestPorts;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class LiveAuditOffTest {

    private static final int ADMIN_PORT = TestPorts.free();
    private static final String ADMIN_TOKEN = "a-long-enough-admin-token-for-tests-0005";

    @DynamicPropertySource
    static void adminPort(DynamicPropertyRegistry registry) {
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
        registry.add("tollgate.admin.token", () -> ADMIN_TOKEN);
    }

    @Test
    void answers409WhenTheAuditTrailIsOff() throws Exception {
        for (String path : new String[] {"/live", "/activity"}) {
            HttpResponse<String> response =
                    TestHttp.get(ADMIN_PORT, "/admin/v1" + path, "Authorization", "Bearer " + ADMIN_TOKEN);

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body())
                    .contains("\"code\":\"audit_off\"")
                    .contains("The live view reads the audit trail, and audit.mode is off");
        }
    }
}
