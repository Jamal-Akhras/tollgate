package io.github.jamalakhras.tollgate.gateway;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.testsupport.FakeProvider;
import io.github.jamalakhras.tollgate.testsupport.TestPorts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "tollgate.max-request-size=16KB",
            "tollgate.max-response-size=16KB",
            "tollgate.max-stream-duration=3s"
        })
abstract class GatewayTestSupport {

    static final FakeProvider PROVIDER = new FakeProvider();
    static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey METERED_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey CODER_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey FRUGAL_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey PLANNER_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey CLERK_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey HASTY_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey RATIONED_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey THRIFTY_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey SINGLE_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey AGENT_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey WATCHED_KEY = ApiKey.generate(new SecureRandom());
    static final ApiKey CHAT_KEY = ApiKey.generate(new SecureRandom());
    static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    static final AuditKeys SIGNING_KEYS =
            AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());
    static final Path AUDIT_DIRECTORY = temporaryDirectory();
    static final Path CONFIG = writeConfig();

    static final String QWEN_ANSWER = "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[{\"index\":0,\"message\":"
            + "{\"role\":\"assistant\",\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}";
    static final String CLOUD_ANSWER =
            "{\"id\":\"c\",\"model\":\"qwen3:235b\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                    + "\"content\":\"Hi\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}";

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        registry.add("tollgate.config", CONFIG::toString);
        registry.add("PROVIDER_KEY", () -> "sk-provider");
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
                  ollama:
                    format: openai
                    baseUrl: "%1$s/v1"
                    apiKey: "${env:PROVIDER_KEY}"
                    region: local
                    extraFields: [top_k]
                  anthropic: { format: anthropic, baseUrl: "%1$s", apiKey: "${env:PROVIDER_KEY}", region: us }
                  slow: { format: openai, baseUrl: "%1$s/v1", timeout: 300ms, region: local }
                  offline: { format: openai, baseUrl: "http://127.0.0.1:%2$d/v1", region: local }
                  gpu: { format: openai, baseUrl: "%1$s/v1", region: local, concurrent: 1 }
                  cloud: { format: openai, baseUrl: "%1$s/v1", region: us }
                  down: { format: openai, baseUrl: "http://127.0.0.1:%2$d/v1", region: local }
                  broken: { format: openai, baseUrl: "http://127.0.0.1:%2$d/v1", region: local }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: free
                    maxOutputTokens: { default: 256, cap: 1024 }
                  llama: { provider: ollama, upstream: "llama3.3:70b", price: free }
                  claude-sonnet:
                    provider: anthropic
                    upstream: claude-sonnet-4-5
                    price: { input: 3, output: 15, cacheRead: 0.30, cacheWrite: 3.75, webSearch: 10 }
                    maxOutputTokens: { default: 1024, cap: 4096 }
                    maxWebSearches: 3
                  frugal-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 0, output: 1000, cacheRead: 0, cacheWrite: 0 }
                    maxOutputTokens: { default: 1000, cap: 1000 }
                  thinking-model:
                    provider: ollama
                    upstream: qwen-plus
                    price: { input: 0, output: 1, reasoning: 4, cacheRead: 0, cacheWrite: 0 }
                  slow-model: { provider: slow, upstream: "qwen3:32b", price: free }
                  offline-model: { provider: offline, upstream: "qwen3:32b", price: free }
                  gpu-model: { provider: gpu, upstream: "qwen3:32b", price: free }
                  cloud-model: { provider: cloud, upstream: "qwen3:235b", price: free }
                  sturdy-model: { provider: down, upstream: "qwen3:32b", price: free, fallback: [cloud-model] }
                  broken-model: { provider: broken, upstream: "qwen3:32b", price: free }
                  mended-model: { provider: broken, upstream: "qwen3:32b", price: free, fallback: [cloud-model] }
                  backed-model: { provider: ollama, upstream: "qwen3:32b", price: free, fallback: [cloud-model] }
                  spill-model: { provider: gpu, upstream: "qwen3:32b", price: free, fallback: [cloud-model] }
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
                  frugal:
                    models: [frugal-model]
                    dataClass: internal
                    budget: { limit: 0.50, period: total }
                  planner:
                    models: [frugal-model]
                    dataClass: internal
                    budget: { limit: 0.50, period: total }
                    showCosts: true
                  coder:
                    models: [qwen3, claude-sonnet, thinking-model, gpu-model, sturdy-model, backed-model, spill-model, broken-model, mended-model]
                    dataClass: internal
                    showCosts: true
                    policy: coding
                  clerk:
                    models: [qwen3, claude-sonnet]
                    dataClass: internal
                    showCosts: true
                    policy: private
                  hasty:
                    models: [qwen3]
                    dataClass: internal
                    policy: slow
                  rationed:
                    models: [qwen3]
                    dataClass: internal
                    limits: { requestsPerMinute: 2 }
                  thrifty:
                    models: [qwen3]
                    dataClass: internal
                    limits: { tokensPerMinute: 100 }
                  single:
                    models: [qwen3]
                    dataClass: internal
                    limits: { concurrent: 1 }
                  agent:
                    models: [claude-sonnet]
                    dataClass: internal
                    policy: agents
                  watched:
                    models: [qwen3, cloud-model]
                    dataClass: internal
                    policy: watching
                  chat:
                    models: [qwen3]
                    dataClass: internal
                    policy: chatting
                    userHeader: X-OpenWebUI-User-Id
                keys:
                %3$s
                detectors:
                  customer-id: { pattern: "CUST-[0-9]{6}" }
                  account-number: { pattern: "account (?<value>[0-9]{8})" }
                  codenames: { terms: [Bluebird, Project Falcon] }
                  backtracking: { pattern: "(.*a){12}b" }
                policies:
                  default:
                    guards:
                      - { type: request-size, maxBytes: 900 }
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                  coding:
                    guards:
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                    features:
                      unreviewed-fields: [internal]
                      remote-images: [internal]
                      web-search: [internal]
                  private:
                    guards:
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                    redact:
                      email: [internal, confidential]
                      card: [internal, confidential]
                      customer-id: [internal, confidential]
                      account-number: [internal, confidential]
                    refuse:
                      secret: [public, internal, confidential]
                      codenames: [internal, confidential]
                  agents:
                    guards:
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                    features:
                      web-search: [internal]
                  watching:
                    guards:
                      - { type: request-size, maxBytes: 10, mode: monitor }
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                  chatting:
                    guards:
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                      - { type: tool-names, refuse: [run_command, "delete_*"] }
                  slow:
                    guards:
                      - type: data-class
                        regions: { public: [us, local], internal: [us, local], confidential: [local] }
                    redact:
                      backtracking: [internal]
                audit:
                  mode: sync
                  directory: '%4$s'
                  publicKey: %5$s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(PROVIDER.baseUrl(), TestPorts.free(), keys(), AUDIT_DIRECTORY, AUDIT_KEYS.publicKey());
        try {
            Path file = Files.createTempFile("tollgate", ".yaml");
            file.toFile().deleteOnExit();
            return Files.writeString(file, config);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String keys() {
        Map<String, ApiKey> byTenant = new LinkedHashMap<>();
        byTenant.put("support-bot", KEY);
        byTenant.put("metered", METERED_KEY);
        byTenant.put("coder", CODER_KEY);
        byTenant.put("frugal", FRUGAL_KEY);
        byTenant.put("planner", PLANNER_KEY);
        byTenant.put("clerk", CLERK_KEY);
        byTenant.put("hasty", HASTY_KEY);
        byTenant.put("rationed", RATIONED_KEY);
        byTenant.put("thrifty", THRIFTY_KEY);
        byTenant.put("single", SINGLE_KEY);
        byTenant.put("agent", AGENT_KEY);
        byTenant.put("watched", WATCHED_KEY);
        byTenant.put("chat", CHAT_KEY);
        return byTenant.entrySet().stream()
                .map(entry -> "  - { id: %s, tenant: %s, sha256: %s }"
                        .formatted(
                                entry.getValue().id(),
                                entry.getKey(),
                                entry.getValue().sha256()))
                .collect(Collectors.joining("\n"));
    }

    @BeforeEach
    void resetProvider() {
        PROVIDER.reset();
    }

    static List<AuditRecord> auditRecordsOf(HttpResponse<String> response) throws IOException {
        return auditRecordsOf(
                response.headers().firstValue(TraceIdFilter.HEADER).orElseThrow());
    }

    static List<AuditRecord> auditRecordsOf(String traceId) throws IOException {
        return auditRecordsIn(AUDIT_DIRECTORY, traceId);
    }

    static List<AuditRecord> auditRecordsIn(Path directory, String traceId) throws IOException {
        List<AuditRecord> records = new ArrayList<>();
        for (Path file : AuditDirectory.files(directory)) {
            for (String line : Files.readAllLines(file)) {
                if (AuditJson.read(line) instanceof AuditRecord record
                        && record.id().equals(traceId)) {
                    records.add(record);
                }
            }
        }
        return records;
    }

    static Intent intentOf(HttpResponse<String> response) throws IOException {
        return auditRecordsOf(response).stream()
                .filter(Intent.class::isInstance)
                .map(Intent.class::cast)
                .findFirst()
                .orElseThrow();
    }

    static Outcome outcomeOf(HttpResponse<String> response) throws IOException {
        return auditRecordsOf(response).stream()
                .filter(Outcome.class::isInstance)
                .map(Outcome.class::cast)
                .findFirst()
                .orElseThrow();
    }

    static double count(String metric, String... tags) {
        Counter counter = Metrics.globalRegistry.find(metric).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    static void awaitProviderCall() {
        while (PROVIDER.received().isEmpty()) {
            Thread.onSpinWait();
        }
    }

    int port() {
        return port;
    }

    HttpResponse<String> management(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + path)));
    }

    HttpResponse<String> chat(ApiKey key, String body, String... headers) throws IOException, InterruptedException {
        return post(Endpoints.OPENAI_CHAT, body, withHeader("Authorization", "Bearer " + key.value(), headers));
    }

    HttpResponse<String> messages(ApiKey key, String body, String... headers) throws IOException, InterruptedException {
        return post(Endpoints.ANTHROPIC_MESSAGES, body, withHeader("x-api-key", key.value(), headers));
    }

    private static String[] withHeader(String name, String value, String... more) {
        String[] all = new String[more.length + 2];
        all[0] = name;
        all[1] = value;
        System.arraycopy(more, 0, all, 2, more.length);
        return all;
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
