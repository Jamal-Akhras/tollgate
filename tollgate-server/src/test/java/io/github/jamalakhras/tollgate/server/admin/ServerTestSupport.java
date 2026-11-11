package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
abstract class ServerTestSupport {

    static final String CHAT = "/v1/chat/completions";
    static final FakeProvider PROVIDER = new FakeProvider();
    static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    static final Path AUDIT_DIRECTORY = temporaryDirectory();
    static final int ADMIN_PORT = TestPorts.free();
    static final String ADMIN_TOKEN = "a-long-enough-admin-token-for-tests-0003";
    static final Path CONFIG = writeConfig();

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("tollgate.config", CONFIG::toString);
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
        registry.add("tollgate.admin.token", () -> ADMIN_TOKEN);
        registry.add("TOLLGATE_AUDIT_SIGNING_KEY", AUDIT_KEYS::signingKey);
        registry.add("TOLLGATE_AUDIT_HMAC_KEY", AUDIT_KEYS::hmacKey);
    }

    private static Path temporaryDirectory() {
        try {
            Path directory = Files.createTempDirectory("tollgate-audit");
            directory.toFile().deleteOnExit();
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path writeConfig() {
        String config =
                """
                dataClasses: [public, internal, confidential]
                providers:
                  ollama: { format: openai, baseUrl: "%1$s/v1", region: local }
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
                    budget: { limit: 0.01, period: total }
                keys:
                  - { id: %2$s, tenant: support-bot, sha256: %3$s }
                policies:
                  default:
                    guards:
                      - type: data-class
                        regions: { public: [local], internal: [local], confidential: [local] }
                audit:
                  mode: sync
                  directory: '%4$s'
                  publicKey: %5$s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(PROVIDER.baseUrl(), KEY.id(), KEY.sha256(), AUDIT_DIRECTORY, AUDIT_KEYS.publicKey());
        try {
            Path file = Files.createTempFile("tollgate", ".yaml");
            file.toFile().deleteOnExit();
            return Files.writeString(file, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
    }

    static List<AuditRecord> auditRecordsOf(HttpResponse<String> response) throws IOException {
        String traceId = response.headers().firstValue("X-Tollgate-Trace-Id").orElseThrow();
        List<AuditRecord> records = new ArrayList<>();
        for (Path file : AuditDirectory.files(AUDIT_DIRECTORY)) {
            for (String line : Files.readAllLines(file)) {
                if (AuditJson.read(line) instanceof AuditRecord record
                        && record.id().equals(traceId)) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    HttpResponse<String> post(String path, String body, String... headers) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
