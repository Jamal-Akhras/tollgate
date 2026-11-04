package io.github.jamalakhras.tollgate.server.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.providers.FakeProvider;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.github.jamalakhras.tollgate.server.audit.AuditJson;
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
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

class BudgetReplayTest {

    private static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    private static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "TOLLGATE_AUDIT_SIGNING_KEY", AUDIT_KEYS.signingKey(), "TOLLGATE_AUDIT_HMAC_KEY", AUDIT_KEYS.hmacKey());
    private static final String TEN_TOKENS =
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":10}}";
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
    private final HttpClient http = ProviderClient.newHttpClient();

    @TempDir
    private Path directory;

    @AfterEach
    void stop() {
        provider.close();
        http.close();
    }

    private Gateway start() throws IOException {
        Path config = Files.writeString(directory.resolve("tollgate.yaml"), """
                providers:
                  ollama: { format: openai, baseUrl: "%s/v1" }
                models:
                  metered-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 0, output: 1000, cacheRead: 0, cacheWrite: 0 }
                    maxOutputTokens: { default: 10, cap: 10 }
                tenants:
                  metered: { models: [metered-model], budget: { limit: 0.05, period: total } }
                keys:
                  - { id: %s, tenant: metered, sha256: %s }
                audit:
                  mode: sync
                  directory: audit
                  publicKey: %s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(
                        provider.baseUrl(), KEY.id(), KEY.sha256(), AUDIT_KEYS.publicKey()));
        return GatewayConfiguration.start(
                ConfigFile.read(config),
                ENVIRONMENT::get,
                new ProviderClient(http, 1_024 * 1_024),
                Clock.systemUTC(),
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1));
    }

    private static HttpStatus call(Gateway gateway) {
        try {
            gateway.complete(
                    "trace-" + System.nanoTime(),
                    WireFormat.OPENAI,
                    Optional.of(KEY.value()),
                    new ByteArrayInputStream(
                            "{\"model\":\"metered-model\",\"messages\":[]}".getBytes(StandardCharsets.UTF_8)),
                    name -> null,
                    NOT_STREAMED);
            return HttpStatus.OK;
        } catch (GatewayException e) {
            return e.status();
        }
    }

    @Test
    void remembersWhatATenantSpentAcrossARestart() throws Exception {
        provider.respond(200, TEN_TOKENS);
        try (Gateway gateway = start()) {
            for (int i = 0; i < 4; i++) {
                assertThat(call(gateway)).isEqualTo(HttpStatus.OK);
            }
        }

        try (Gateway restarted = start()) {
            assertThat(call(restarted)).isEqualTo(HttpStatus.OK);
            assertThat(call(restarted)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test
    void chargesTheFullReservationOfACallThatNeverFinished() throws Exception {
        try (Gateway gateway = start()) {
            assertThat(gateway.auditHealthy()).isTrue();
        }
        Intent unfinished = new Intent(
                "crashed",
                Instant.now(),
                "metered",
                KEY.id(),
                WireFormat.OPENAI,
                "metered-model",
                "ollama",
                "qwen3:32b",
                null,
                "a".repeat(64),
                "b".repeat(64),
                List.of(),
                Decision.ALLOW,
                Money.of(new BigDecimal("0.05"), Currency.getInstance("USD")));
        Files.writeString(
                directory.resolve("audit").resolve("tollgate-audit-00000099.jsonl"),
                AuditJson.write(unfinished) + "\n");
        provider.respond(200, TEN_TOKENS);

        try (Gateway restarted = start()) {
            assertThat(call(restarted)).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    @Test
    void skipsALineTornByACrashButRefusesToStartOnAnUnreadableOne() throws Exception {
        try (Gateway gateway = start()) {
            assertThat(gateway.auditHealthy()).isTrue();
        }
        Path audit = directory.resolve("audit");
        Files.writeString(audit.resolve("tollgate-audit-00000098.jsonl"), "{\"v\":1,\"type\":\"inte");
        try (Gateway restarted = start()) {
            assertThat(restarted.auditHealthy()).isTrue();
        }

        Files.writeString(audit.resolve("tollgate-audit-00000097.jsonl"), "not json\n");
        assertThatThrownBy(this::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be read; run audit verify");
    }
}
