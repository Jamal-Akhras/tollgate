package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import io.github.jamalakhras.tollgate.providers.TestPorts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.function.ThrowingSupplier;
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

    @LocalServerPort
    int gatewayPort;

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
        String[] all = Stream.concat(Stream.of("Content-Type", "application/json"), Stream.of(headers))
                .toArray(String[]::new);
        return TestHttp.send("POST", gatewayPort, path, body, all);
    }

    static HttpResponse<String> admin(String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        String[] all = Stream.concat(Stream.of("Authorization", "Bearer " + ADMIN_TOKEN), Stream.of(headers))
                .toArray(String[]::new);
        return TestHttp.send(method, ADMIN_PORT, "/admin/v1" + path, body, all);
    }

    static HttpResponse<String> admin(String path) throws IOException, InterruptedException {
        return admin("GET", path, "");
    }

    static HttpResponse<String> reload() throws IOException, InterruptedException {
        return admin("POST", "/config/reload", "");
    }

    static String versionIn(HttpResponse<String> reloaded) {
        return reloaded.body().replaceAll(".*\"version\":\"([0-9a-f]{64})\".*", "$1");
    }

    static <T> T poll(ThrowingSupplier<T> read, Predicate<T> done) throws Throwable {
        T value = read.get();
        for (int i = 0; i < 50 && !done.test(value); i++) {
            Thread.sleep(100);
            value = read.get();
        }
        return value;
    }

    HttpResponse<String> answeredCall() throws IOException, InterruptedException {
        PROVIDER.respond(200, "{\"id\":\"c\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        return post(CHAT, "{\"model\":\"qwen3\",\"messages\":[]}", "Authorization", "Bearer " + KEY.value());
    }
}
