package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditSink;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.InMemoryBudgetStore;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Tracer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class AuditFailureTest {

    private static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    private static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    private static final Budget BUDGET =
            new Budget(Money.of(new BigDecimal("0.01"), Currency.getInstance("USD")), Period.TOTAL);
    private static final String REQUEST = "{\"model\":\"metered-model\",\"messages\":[]}";

    private final FakeProvider provider = new FakeProvider();
    private final HttpClient httpClient = ProviderClient.newHttpClient();
    private final InMemoryBudgetStore budgets = new InMemoryBudgetStore(Clock.systemUTC());

    @TempDir
    private Path directory;

    @AfterEach
    void stop() {
        provider.close();
        httpClient.close();
    }

    private Gateway gateway(AuditSink sink) throws IOException {
        Path config = Files.writeString(
                directory.resolve("tollgate.yaml"), """
                providers:
                  ollama: { format: openai, baseUrl: "%s/v1" }
                models:
                  metered-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 1, output: 2, cacheRead: 1, cacheWrite: 1 }
                    maxOutputTokens: { default: 1000, cap: 1000 }
                tenants:
                  metered: { models: [metered-model], budget: { limit: 0.01, period: total } }
                keys:
                  - { id: %s, tenant: metered, sha256: %s }
                audit: { mode: off }
                """.formatted(provider.baseUrl(), KEY.id(), KEY.sha256()));
        AuditKeys keys = AuditKeys.of(AUDIT_KEYS.publicKey(), AUDIT_KEYS.signingKey(), AUDIT_KEYS.hmacKey());
        return new Gateway(
                ConfigFile.read(config),
                name -> null,
                new ProviderClient(httpClient, 1_024 * 1_024),
                budgets,
                AuditLog.start(sink, keys, Clock.systemUTC(), true),
                Clock.systemUTC(),
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Tracer.NOOP);
    }

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

    private static ByteArrayInputStream body() {
        return new ByteArrayInputStream(REQUEST.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void refusesARequestWhoseIntentCannotBeWrittenAndReleasesItsReservation() throws Exception {
        try (Gateway gateway = gateway(new FailingSink(0))) {
            assertThatThrownBy(() -> gateway.complete(
                            "trace", WireFormat.OPENAI, Optional.of(KEY.value()), body(), h -> null, NOT_STREAMED))
                    .isInstanceOfSatisfying(GatewayException.class, e -> {
                        assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(e.code()).isEqualTo("audit_unavailable");
                    });

            assertThat(provider.received()).isEmpty();
            assertThat(gateway.auditHealthy()).isFalse();
            assertThat(budgets.reserve("metered", BUDGET, BUDGET.limit())).isPresent();
        }
    }

    @Test
    void returnsTheResponseWhenOnlyTheOutcomeCannotBeWritten() throws Exception {
        provider.respond(200, "{\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        try (Gateway gateway = gateway(new FailingSink(1))) {
            var response = gateway.complete(
                            "trace", WireFormat.OPENAI, Optional.of(KEY.value()), body(), h -> null, NOT_STREAMED)
                    .orElseThrow();

            assertThat(response.status()).isEqualTo(200);
            assertThat(provider.received()).hasSize(1);
        }
    }

    private static final class FailingSink implements AuditSink {

        private final AtomicInteger writesLeft;

        FailingSink(int successfulWrites) {
            this.writesLeft = new AtomicInteger(successfulWrites);
        }

        @Override
        public Optional<Seal> lastSeal() {
            return Optional.empty();
        }

        @Override
        public void write(List<AuditEntry> entries) throws IOException {
            if (writesLeft.getAndDecrement() <= 0) {
                throw new IOException("No space left on device");
            }
        }

        @Override
        public void close() {}
    }
}
