package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.Model;
import io.github.jamalakhras.tollgate.core.config.OutputTokens;
import io.github.jamalakhras.tollgate.core.config.Provider;
import io.github.jamalakhras.tollgate.core.config.SecretRef;
import io.github.jamalakhras.tollgate.core.config.Tenant;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.limit.Limits;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.TokenPrice;
import io.github.jamalakhras.tollgate.core.money.TokenRates;
import io.github.jamalakhras.tollgate.core.policy.MonitoredGuard;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.ToolNamesGuard;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigViewTest extends ServerTestSupport {

    @Test
    void showsTheRunningConfig() throws Exception {
        HttpResponse<String> response = admin("/config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"edits\":\"read-only\",\"rewritten\":false")
                .contains("\"name\":\"support-bot\"")
                .contains("\"policy\":\"default\"")
                .contains("\"type\":\"data-class\",\"mode\":\"enforce\"")
                .contains("\"regions\":{\"confidential\":[\"local\"],\"internal\":[\"local\"],\"public\":[\"local\"]}")
                .contains("\"host\":\"" + URI.create(PROVIDER.baseUrl()).getAuthority() + "\"")
                .contains("\"name\":\"qwen3\",\"provider\":\"ollama\",\"upstream\":\"qwen3:32b\",\"free\":true")
                .contains("\"id\":\"" + KEY.id() + "\"")
                .contains("\"audit\":{\"mode\":\"sync\",\"publicKey\":\"" + AUDIT_KEYS.publicKey() + "\"}")
                .doesNotContain(KEY.sha256());
    }

    @Test
    void showsTheVersionAReloadReports() throws Exception {
        String version = versionIn(reload());

        assertThat(admin("/config").body()).contains("\"version\":\"" + version + "\"");
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(TestHttp.get(ADMIN_PORT, "/admin/v1/config").statusCode()).isEqualTo(401);
    }

    @Test
    void answersOnlyOnTheAdminPort() throws Exception {
        assertThat(TestHttp.get(gatewayPort, "/admin/v1/config").statusCode()).isEqualTo(404);
    }

    @Test
    void mapsGuardsBudgetsLimitsAndSecrets() {
        Currency usd = Currency.getInstance("USD");
        TokenPrice cheap = new TokenPrice(new Money(1, usd));
        TokenPrice dear = new TokenPrice(new Money(2, usd));
        ModelPrice price = new ModelPrice(new TokenRates(cheap, dear, dear, cheap, cheap), List.of(), null);
        var provider = new Provider(
                WireFormat.OPENAI,
                URI.create("https://api.example.com:8443/v1"),
                new SecretRef("EXAMPLE_KEY"),
                "eu",
                Duration.ofSeconds(30),
                List.of(),
                null,
                5L);
        var config = new GatewayConfig(
                List.of(),
                Map.of("example", provider),
                Map.of("paid", new Model("example", "gpt", price, new OutputTokens(100, 500))),
                Map.of(
                        "app",
                        new Tenant(
                                List.of("paid"),
                                null,
                                null,
                                new Budget(Money.of(new BigDecimal("50.00"), usd), Period.MONTHLY),
                                true,
                                new Limits(60L, null, 4L),
                                "X-User")),
                List.of(),
                Map.of(
                        "default",
                        new Policy(List.of(new MonitoredGuard(new ToolNamesGuard(List.of(), List.of("shell")))))),
                new AuditSettings.Enabled(false, "audit", "public", new SecretRef("SIGN"), new SecretRef("HMAC")));

        var view = ConfigViewController.view("abc", false, false, config);

        assertThat(view.providers().getFirst())
                .isEqualTo(new ConfigViewController.ProviderView(
                        "example", "openai", "api.example.com:8443", "eu", 5L, "${env:EXAMPLE_KEY}"));
        assertThat(view.models().getFirst().free()).isFalse();
        assertThat(view.models().getFirst().outputCap()).isEqualTo(500L);
        assertThat(view.tenants().getFirst())
                .isEqualTo(new ConfigViewController.TenantView(
                        "app",
                        null,
                        "default",
                        List.of("paid"),
                        new ConfigViewController.BudgetView("50", "USD", "monthly"),
                        new ConfigViewController.LimitsView(60L, null, 4L),
                        true,
                        true));
        assertThat(view.policies().getFirst().guards())
                .containsExactly(new ConfigViewController.GuardView(
                        "tool-names", "monitor", null, null, List.of(), List.of("shell")));
        assertThat(view.audit()).isEqualTo(new ConfigViewController.AuditView("async", "public"));
    }
}
