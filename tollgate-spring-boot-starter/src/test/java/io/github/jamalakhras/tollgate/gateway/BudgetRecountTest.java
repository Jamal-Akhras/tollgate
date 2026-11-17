package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Reservation;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.gateway.audit.AuditJson;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.micrometer.tracing.Tracer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class BudgetRecountTest {

    private static final ApiKey KEY = ApiKey.generate(new SecureRandom());
    private static final AuditKeys.NewKeys AUDIT_KEYS = AuditKeys.generate(new SecureRandom());
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "TOLLGATE_AUDIT_SIGNING_KEY", AUDIT_KEYS.signingKey(), "TOLLGATE_AUDIT_HMAC_KEY", AUDIT_KEYS.hmacKey());
    private static final Currency USD = Currency.getInstance("USD");
    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final Instant EARLIER_THIS_MONTH = Instant.parse("2026-11-02T09:00:00Z");
    private static final Instant THIS_MORNING = Instant.parse("2026-11-16T08:00:00Z");

    private final HttpClient http = ProviderClient.newHttpClient();

    @TempDir
    private Path directory;

    private Path config() {
        return directory.resolve("tollgate.yaml");
    }

    @AfterEach
    void stop() {
        http.close();
    }

    private void write(String budget, boolean audited) throws IOException {
        Files.writeString(config(), """
                providers:
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1" }
                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:8b"
                    price: free
                    maxOutputTokens: { default: 1024, cap: 4096 }
                tenants:
                  metered: { models: [qwen3]%s }
                keys:
                  - { id: %s, tenant: metered, sha256: %s }
                %s
                """.formatted(
                        budget,
                        KEY.id(),
                        KEY.sha256(),
                        audited ? """
                        audit:
                          mode: sync
                          directory: audit
                          publicKey: %s
                          signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                          hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                        """.formatted(AUDIT_KEYS.publicKey()) : "audit: { mode: off }"));
    }

    private Gateway start() {
        return GatewayConfiguration.start(
                ConfigFile.read(config()),
                ENVIRONMENT::get,
                new ProviderClient(http, 1_024 * 1_024),
                Clock.fixed(NOW, ZoneOffset.UTC),
                1_000_000,
                Duration.ofMinutes(1),
                Duration.ofSeconds(1),
                Tracer.NOOP);
    }

    private void spend(String file, String id, Instant at, String cost) throws IOException {
        Intent intent = new Intent(
                id,
                at,
                "metered",
                KEY.id(),
                WireFormat.OPENAI,
                "qwen3",
                "ollama",
                "qwen3:8b",
                null,
                "a".repeat(64),
                "b".repeat(64),
                List.of(),
                Decision.ALLOW,
                Money.of(new BigDecimal("0.50"), USD));
        Outcome outcome = new Outcome(
                id,
                at,
                Outcome.Result.RESPONDED,
                200,
                new Usage(0, 40, 0, 0),
                Money.of(new BigDecimal(cost), USD),
                Duration.ofMillis(5),
                "c".repeat(64));
        Files.writeString(
                directory.resolve("audit").resolve(file),
                AuditJson.write(intent) + "\n" + AuditJson.write(outcome) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private void spendThisMonth() throws IOException {
        spend("tollgate-audit-00000098.jsonl", "earlier", EARLIER_THIS_MONTH, "0.40");
        spend("tollgate-audit-00000098.jsonl", "morning", THIS_MORNING, "0.30");
    }

    private static Budget budgetOf(Gateway gateway) {
        return Objects.requireNonNull(
                Objects.requireNonNull(gateway.config().tenants().get("metered"))
                        .budget());
    }

    private static Money spent(Gateway gateway) {
        return gateway.budgetStore().spent("metered", budgetOf(gateway));
    }

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), USD);
    }

    @SuppressWarnings("unchecked")
    private static ConfigOperation budget(String limit, String period) {
        return new ConfigOperation("budget", "tenants.metered.budget", document -> {
            Map<String, Object> tenants = (Map<String, Object>) Objects.requireNonNull(document.get("tenants"));
            Map<String, Object> tenant = (Map<String, Object>) Objects.requireNonNull(tenants.get("metered"));
            tenant.put("budget", Map.of("limit", new BigDecimal(limit), "period", period));
            return document;
        });
    }

    @Test
    void aReloadToADailyBudgetCountsOnlyTodaysSpend() throws Exception {
        write(", budget: { limit: 5, period: monthly }", true);
        try (Gateway gateway = start()) {
            spendThisMonth();

            write(", budget: { limit: 5, period: daily }", true);
            gateway.reload();

            assertThat(spent(gateway)).isEqualTo(usd("0.30"));
        }
    }

    @Test
    void anEditToADailyBudgetCountsOnlyTodaysSpend() throws Exception {
        write(", budget: { limit: 5, period: monthly }", true);
        try (Gateway gateway = start()) {
            spendThisMonth();

            gateway.edit(gateway.configVersion(), List.of(budget("5", "daily")));

            assertThat(spent(gateway)).isEqualTo(usd("0.30"));
        }
    }

    @Test
    void aFirstBudgetPicksUpSpendAlreadyInTheTrail() throws Exception {
        write("", true);
        try (Gateway gateway = start()) {
            spendThisMonth();

            write(", budget: { limit: 5, period: monthly }", true);
            gateway.reload();

            assertThat(spent(gateway)).isEqualTo(usd("0.70"));
        }
    }

    @Test
    void aNewLimitKeepsTheSpendAndDoesNotReadTheTrail() throws Exception {
        write(", budget: { limit: 5, period: monthly }", true);
        start().close();
        spend("tollgate-audit-00000098.jsonl", "before", EARLIER_THIS_MONTH, "0.40");
        try (Gateway gateway = start()) {
            assertThat(spent(gateway)).isEqualTo(usd("0.40"));
            spend("tollgate-audit-00000098.jsonl", "after", THIS_MORNING, "0.30");

            write(", budget: { limit: 9, period: monthly }", true);
            gateway.reload();

            assertThat(spent(gateway)).isEqualTo(usd("0.40"));
        }
    }

    @Test
    void warnsAboutEachTenantWhoseSpendCannotBeCountedBecauseTheAuditTrailIsOff(CapturedOutput output)
            throws Exception {
        write(", budget: { limit: 5, period: monthly }", false);
        try (Gateway gateway = start()) {
            write(", budget: { limit: 5, period: daily }", false);
            gateway.reload();

            assertThat(spent(gateway)).isEqualTo(usd("0"));
            assertThat(output.getAll()).contains("The budget of tenant metered changed period or is new");
        }
    }

    @Test
    void aCallReservedBeforeTheRecountKeepsItWhenItSettlesAfter() throws Exception {
        write(", budget: { limit: 5, period: monthly }", true);
        try (Gateway gateway = start()) {
            Budget monthly = budgetOf(gateway);
            Reservation inFlight =
                    gateway.budgetStore().reserve("metered", monthly, usd("1")).orElseThrow();
            spendThisMonth();

            write(", budget: { limit: 5, period: daily }", true);
            gateway.reload();
            gateway.budgetStore().settle(inFlight, usd("0.20"));

            assertThat(spent(gateway)).isEqualTo(usd("0.50"));
        }
    }

    @Test
    void aCallStartedOnTheOldConfigAfterTheRecountKeepsItToo() throws Exception {
        write(", budget: { limit: 5, period: monthly }", true);
        try (Gateway gateway = start()) {
            Budget monthly = budgetOf(gateway);
            spendThisMonth();

            write(", budget: { limit: 5, period: daily }", true);
            gateway.reload();
            Reservation late =
                    gateway.budgetStore().reserve("metered", monthly, usd("1")).orElseThrow();
            gateway.budgetStore().settle(late, usd("0.20"));

            assertThat(spent(gateway)).isEqualTo(usd("0.50"));
        }
    }
}
