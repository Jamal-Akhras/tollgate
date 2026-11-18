package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.providers.TestPorts;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConsoleServingTest {

    private static final int ADMIN_PORT = TestPorts.free();
    private static final String ADMIN_TOKEN = "a-long-enough-admin-token-for-tests-0004";

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void adminPort(DynamicPropertyRegistry registry) {
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
        registry.add("tollgate.admin.token", () -> ADMIN_TOKEN);
    }

    @Test
    void servesTheConsoleOnTheAdminPortWithItsHeaders() throws Exception {
        HttpResponse<String> response = fetch(ADMIN_PORT, "/console/");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<title>console</title>");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(ConsoleHeadersFilter.POLICY);
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .hasValue("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; "
                        + "connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'");
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(response.headers().firstValue("Cross-Origin-Opener-Policy")).hasValue("same-origin");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-cache");
    }

    @Test
    void servesAssetsAsImmutableJavaScript() throws Exception {
        HttpResponse<String> response = fetch(ADMIN_PORT, "/console/assets/app-0a1b2c3d.js");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElseThrow()).contains("javascript");
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("public, max-age=31536000, immutable");
        assertThat(response.headers().firstValue("Content-Security-Policy")).hasValue(ConsoleHeadersFilter.POLICY);
    }

    @Test
    void redirectsTheRootAndTheBareConsolePathToTheConsole() throws Exception {
        for (String path : new String[] {"/", "/console"}) {
            HttpResponse<String> response = fetch(ADMIN_PORT, path);

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location").orElseThrow()).endsWith("/console/");
        }
    }

    @Test
    void answers404ForTheConsoleOnTheGatewayPort() throws Exception {
        assertThat(fetch(gatewayPort, "/console/").statusCode()).isEqualTo(404);
        assertThat(fetch(gatewayPort, "/").statusCode()).isEqualTo(404);
    }

    @Test
    void needsNoToken() throws Exception {
        assertThat(fetch(ADMIN_PORT, "/console/").statusCode()).isEqualTo(200);
        assertThat(fetch(ADMIN_PORT, "/admin/v1/info").statusCode()).isEqualTo(401);
    }

    @Test
    void answers404ForMissingFilesAndPathTraversal() throws Exception {
        assertThat(fetch(ADMIN_PORT, "/console/missing.js").statusCode()).isEqualTo(404);
        assertThat(fetch(ADMIN_PORT, "/console/..%2f..%2fapplication.yaml").statusCode())
                .isEqualTo(400);
    }

    @Test
    void servesTheBuiltConsole() throws Exception {
        var pages = ConsoleServingTest.class.getClassLoader().getResources("META-INF/tollgate-console/index.html");
        var urls = Collections.list(pages);
        assertThat(urls).hasSize(2);

        boolean namesAScript = false;
        for (var url : urls) {
            try (var stream = url.openStream()) {
                namesAScript |= new String(stream.readAllBytes(), StandardCharsets.UTF_8).contains("assets/");
            }
        }
        assertThat(namesAScript).isTrue();
    }

    private static HttpResponse<String> fetch(int port, String path) throws IOException, InterruptedException {
        return CLIENT.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
