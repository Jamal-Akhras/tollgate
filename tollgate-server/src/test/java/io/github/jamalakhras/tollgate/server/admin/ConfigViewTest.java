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
import io.github.jamalakhras.tollgate.core.policy.MonitoredGuard;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.ToolNamesGuard;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

class ConfigViewTest extends ServerTestSupport {

    @LocalServerPort
    private int gatewayPort;

    private static HttpResponse<String> get(String url, String... headers) throws IOException, InterruptedException {
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url));
            if (headers.length > 0) {
                request.headers(headers);
            }
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpResponse<String> admin(String path) throws IOException, InterruptedException {
        return get("http://127.0.0.1:" + ADMIN_PORT + path, "Authorization", "Bearer " + ADMIN_TOKEN);
    }

    @Test
    void showsTheRunningConfig() throws Exception {
        HttpResponse<String> response = admin("/admin/v1/config");

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
        String reloaded;
        try (HttpClient client = HttpClient.newHttpClient()) {
            reloaded = client.send(
                            HttpRequest.newBuilder(
                                            URI.create("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1/config/reload"))
                                    .header("Authorization", "Bearer " + ADMIN_TOKEN)
                                    .POST(HttpRequest.BodyPublishers.noBody())
                                    .build(),
                            HttpResponse.BodyHandlers.ofString())
                    .body();
        }
        String marker = "\"version\":\"";
        int start = reloaded.indexOf(marker) + marker.length();
        String version = reloaded.substring(start, reloaded.indexOf('"', start));

        assertThat(admin("/admin/v1/config").body()).contains(marker + version + "\"");
    }

    @Test
    void needsTheAdminToken() throws Exception {
        assertThat(get("http://127.0.0.1:" + ADMIN_PORT + "/admin/v1/config").statusCode())
                .isEqualTo(401);
    }

    @Test
    void answersOnlyOnTheAdminPort() throws Exception {
        assertThat(get("http://127.0.0.1:" + gatewayPort + "/admin/v1/config").statusCode())
                .isEqualTo(404);
    }

    @Test
    void mapsGuardsBudgetsLimitsAndSecrets() {
        Currency usd = Currency.getInstance("USD");
        ModelPrice price = new ModelPrice(
                new TokenPrice(new Money(1, usd)),
                new TokenPrice(new Money(2, usd)),
                new TokenPrice(new Money(1, usd)),
                new TokenPrice(new Money(1, usd)));
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
