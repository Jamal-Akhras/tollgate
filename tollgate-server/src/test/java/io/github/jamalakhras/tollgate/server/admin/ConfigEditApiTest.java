package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.server.TestPorts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConfigEditApiTest {

    private static final int ADMIN_PORT = TestPorts.free();
    private static final String ADMIN_TOKEN = "a-long-enough-admin-token-for-tests-0004";
    private static final Path FOLDER = temporaryFolder();
    private static final Path CONFIG = FOLDER.resolve("tollgate.yaml");
    private static final String TEXT = handWritten();

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("tollgate.config", CONFIG::toString);
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
        registry.add("tollgate.admin.token", () -> ADMIN_TOKEN);
        registry.add("tollgate.admin.edits", () -> "file");
    }

    private static Path temporaryFolder() {
        try {
            Path folder = Files.createTempDirectory("tollgate-edits");
            folder.toFile().deleteOnExit();
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String handWritten() {
        ApiKey key = ApiKey.generate(new SecureRandom());
        String text = """
                # Written by hand
                dataClasses: [public, internal]
                providers:
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }
                models:
                  qwen3: { provider: ollama, upstream: "qwen3:32b", price: free }
                  metered-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 1, output: 2, cacheRead: 1, cacheWrite: 1 }
                    maxOutputTokens: { default: 1000, cap: 1000 }
                tenants:
                  support-bot:
                    models: [qwen3]
                    dataClass: internal
                  metered:
                    models: [metered-model]
                    dataClass: internal
                    budget: { limit: 50, period: monthly }
                keys:
                  - { id: %s, tenant: support-bot, sha256: %s }
                policies:
                  default:
                    guards:
                      - type: request-size
                        maxBytes: 1000000
                      - type: data-class
                        regions: { public: [local], internal: [local] }
                audit: { mode: off }
                """.formatted(key.id(), key.sha256());
        try {
            Files.writeString(CONFIG, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return text;
    }

    @AfterEach
    void putTheHandWrittenFileBack() throws Exception {
        Files.writeString(CONFIG, TEXT);
        Files.deleteIfExists(Path.of(CONFIG + ".original"));
        send("POST", "/admin/v1/config/reload");
    }

    private static HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ADMIN_PORT + path))
                            .header("Authorization", "Bearer " + ADMIN_TOKEN)
                            .method(method, HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long versionsKept() throws IOException {
        try (Stream<Path> files = Files.list(FOLDER.resolve("config-history"))) {
            return files.count();
        }
    }

    @Test
    void showsThatTheConfigIsManagedAndNotYetRewritten() throws Exception {
        HttpResponse<String> response = send("GET", "/admin/v1/config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"edits\":\"managed\",\"rewritten\":false");
    }

    @Test
    void keepsTheVersionItStartedWithAndEachVersionAReloadBrings() throws Exception {
        long before = versionsKept();
        assertThat(before).isPositive();

        Files.writeString(CONFIG, TEXT + "# changed by hand\n");
        HttpResponse<String> reloaded = send("POST", "/admin/v1/config/reload");

        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(reloaded.body()).contains("\"changed\":true");
        assertThat(versionsKept()).isEqualTo(before + 1);
    }
}
