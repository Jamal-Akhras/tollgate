package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditSink;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.budget.InMemoryBudgetStore;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TracingTest {

    private static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    private static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    private static final String PROMPT = "My card is 4111 1111 1111 1111";

    private static final StreamTarget NOT_STREAMED = new StreamTarget() {
        @Override
        public void open(Map<String, String> headers) {
            throw new AssertionError("Not a streamed request");
        }

        @Override
        public void write(byte[] event) {
            throw new AssertionError("Not a streamed request");
        }
    };

    private final FakeProvider provider = new FakeProvider();
    private final HttpClient httpClient = ProviderClient.newHttpClient();
    private final InMemorySpanExporter spans = InMemorySpanExporter.create();
    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(spans))
            .build();
    private final Tracer tracer =
            new OtelTracer(tracerProvider.get("test"), new OtelCurrentTraceContext(), event -> {});

    @TempDir
    private Path directory;

    @AfterEach
    void stop() {
        provider.close();
        httpClient.close();
        tracerProvider.close();
    }

    private Gateway gateway() throws Exception {
        Path config = Files.writeString(
                directory.resolve("tollgate.yaml"), """
                providers:
                  ollama: { format: openai, baseUrl: "%s/v1" }
                models:
                  qwen3: { provider: ollama, upstream: "qwen3:32b", price: free }
                tenants:
                  support-bot: { models: [qwen3] }
                keys:
                  - { id: %s, tenant: support-bot, sha256: %s }
                audit: { mode: off }
                """.formatted(provider.baseUrl(), KEY.id(), KEY.sha256()));
        AuditKeys keys = AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());
        return new Gateway(
                ConfigFile.read(config),
                name -> null,
                new ProviderClient(httpClient, 1_024 * 1_024),
                new InMemoryBudgetStore(Clock.systemUTC()),
                AuditLog.start(new DiscardingSink(), keys, Clock.systemUTC(), true),
                Clock.systemUTC(),
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                tracer);
    }

    private static String attribute(SpanData span, String key) {
        return String.valueOf(span.getAttributes().get(AttributeKey.stringKey(key)));
    }

    @Test
    void tracesEachStepUnderTheCallersSpanWithoutTheContent() throws Exception {
        provider.respond(
                200,
                "{\"id\":\"c\",\"model\":\"qwen3:32b\",\"choices\":[],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4}}");
        String body = "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\",\"content\":\"" + PROMPT + "\"}]}";
        Span parent = tracer.nextSpan().name("POST /v1/chat/completions").start();
        try (Gateway gateway = gateway()) {
            Tracer.SpanInScope scope = tracer.withSpan(parent);
            try {
                gateway.complete(
                        "trace-1",
                        WireFormat.OPENAI,
                        Optional.of(KEY.value()),
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                        name -> null,
                        NOT_STREAMED);
            } finally {
                scope.close();
                parent.end();
            }
        }

        List<SpanData> finished = spans.getFinishedSpanItems();
        assertThat(finished)
                .extracting(SpanData::getName)
                .contains(
                        "tollgate.guards",
                        "tollgate.redaction",
                        "tollgate.budget",
                        "tollgate.audit",
                        "chat qwen3:32b",
                        "POST /v1/chat/completions");
        assertThat(finished)
                .extracting(SpanData::getTraceId)
                .containsOnly(parent.context().traceId());
        SpanData request = finished.stream()
                .filter(span -> span.getName().startsWith("POST"))
                .findFirst()
                .orElseThrow();
        assertThat(attribute(request, "tollgate.trace_id")).isEqualTo("trace-1");
        assertThat(attribute(request, "tollgate.tenant")).isEqualTo("support-bot");
        SpanData call = finished.stream()
                .filter(span -> span.getName().equals("chat qwen3:32b"))
                .findFirst()
                .orElseThrow();
        assertThat(call.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(attribute(call, "gen_ai.provider.name")).isEqualTo("ollama");
        assertThat(attribute(call, "gen_ai.request.model")).isEqualTo("qwen3:32b");
        assertThat(attribute(call, "gen_ai.usage.input_tokens")).isEqualTo("10");
        assertThat(attribute(call, "gen_ai.usage.output_tokens")).isEqualTo("4");
        assertThat(attribute(call, "tollgate.result")).isEqualTo("responded");
        assertThat(finished)
                .allSatisfy(span -> assertThat(span.getAttributes().toString()).doesNotContain("4111"));
        assertThat(provider.received())
                .singleElement()
                .satisfies(received -> assertThat(received.headers()).doesNotContainKeys("Traceparent", "traceparent"));
    }

    private static final class DiscardingSink implements AuditSink {

        @Override
        public Optional<Seal> lastSeal() {
            return Optional.empty();
        }

        @Override
        public void write(List<AuditEntry> entries) {}

        @Override
        public void close() {}
    }
}
