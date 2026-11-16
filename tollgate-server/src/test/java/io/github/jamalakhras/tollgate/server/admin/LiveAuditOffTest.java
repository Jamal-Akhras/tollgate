package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.server.TestPorts;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
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

    private static HttpResponse<String> admin(String path) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1" + path))
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Test
    void answers409WhenTheAuditTrailIsOff() throws Exception {
        for (String path : new String[] {"/live", "/activity"}) {
            HttpResponse<String> response = admin(path);

            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(response.body())
                    .contains("\"code\":\"audit_off\"")
                    .contains("The live view reads the audit trail, and audit.mode is off");
        }
    }
}
