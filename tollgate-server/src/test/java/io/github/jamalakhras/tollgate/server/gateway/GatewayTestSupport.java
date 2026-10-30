package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
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
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "tollgate.max-request-size=1KB"})
abstract class GatewayTestSupport {

    static final FakeProvider PROVIDER = new FakeProvider();
    static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey METERED_KEY = ApiKey.generate(new SecureRandom());

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("tollgate.config", GatewayTestSupport::writeConfig);
        registry.add("tollgate.admin.port", TestPorts::free);
        registry.add("PROVIDER_KEY", () -> "sk-provider");
    }

    private static String writeConfig() {
        String config = """
                dataClasses: [public, internal, confidential]
                providers:
                  ollama: { format: openai, baseUrl: "%1$s/v1", apiKey: "${env:PROVIDER_KEY}", region: local }
                  anthropic: { format: anthropic, baseUrl: "%1$s", apiKey: "${env:PROVIDER_KEY}", region: us }
                  slow: { format: openai, baseUrl: "%1$s/v1", timeout: 300ms, region: local }
                  offline: { format: openai, baseUrl: "http://127.0.0.1:%2$d/v1", region: local }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }
                    maxOutputTokens: { default: 256, cap: 1024 }
                  llama: { provider: ollama, upstream: "llama3.3:70b", price: %5$s }
                  claude-sonnet:
                    provider: anthropic
                    upstream: claude-sonnet-4-5
                    price: { input: 3, output: 15, cacheRead: 0.30, cacheWrite: 3.75 }
                    maxOutputTokens: { default: 1024, cap: 4096 }
                  slow-model: { provider: slow, upstream: "qwen3:32b", price: %5$s }
                  offline-model: { provider: offline, upstream: "qwen3:32b", price: %5$s }
                  metered-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 1, output: 2, cacheRead: 1, cacheWrite: 1 }
                    maxOutputTokens: { default: 1000, cap: 1000 }
                tenants:
                  support-bot:
                    models: [qwen3, claude-sonnet, slow-model, offline-model]
                    dataClass: internal
                  metered:
                    models: [metered-model]
                    dataClass: internal
                    budget: { limit: 0.01, period: total }
                keys:
                  - { id: %3$s, tenant: support-bot, sha256: %4$s }
                  - { id: %6$s, tenant: metered, sha256: %7$s }
                policies:
                  default:
                    guards:
                      - { type: request-size, maxBytes: 900 }
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                """.formatted(
                        PROVIDER.baseUrl(),
                        TestPorts.free(),
                        KEY.id(),
                        KEY.sha256(),
                        "{ input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }",
                        METERED_KEY.id(),
                        METERED_KEY.sha256());
        try {
            Path file = Files.createTempFile("tollgate", ".yaml");
            file.toFile().deleteOnExit();
            return Files.writeString(file, config).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
    }

    HttpResponse<String> post(String path, String body, String... headers) throws IOException, InterruptedException {
        return send(request(path, headers).POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    HttpResponse<String> get(String path, String... headers) throws IOException, InterruptedException {
        return send(request(path, headers).GET());
    }

    private HttpRequest.Builder request(String path, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (headers.length > 0) {
            request.headers(headers);
        }
        return request;
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
