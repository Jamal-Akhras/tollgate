package io.github.jamalakhras.tollgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.Prices;
import io.github.jamalakhras.tollgate.core.money.SearchPrice;
import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import io.github.jamalakhras.tollgate.core.policy.RequestSizeGuard;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class GatewayConfigTest {

    private static final List<String> DATA_CLASSES = List.of("public", "internal", "confidential");
    private static final Provider OLLAMA = provider("local");
    private static final Provider ANTHROPIC = provider("us");
    private static final ModelPrice FREE = Prices.perMillion("0", "0", "0", "0");
    private static final OutputTokens OUTPUT = new OutputTokens(1_024, 4_096);
    private static final Model QWEN = new Model("ollama", "qwen3:32b", FREE, OUTPUT);
    private static final Map<String, Model> MODELS = Map.of(
            "qwen3", QWEN,
            "llama", new Model("ollama", "llama3.3:70b", FREE, null),
            "claude-sonnet", new Model("anthropic", "claude-sonnet-4-5", FREE, OUTPUT));
    private static final Policy STRICT = new Policy(List.of(
            new RequestSizeGuard(1_000),
            new DataClassGuard(Map.of(
                    "public", List.of("us", "local"),
                    "internal", List.of("us", "local"),
                    "confidential", List.of("local")))));
    private static final Policy ANY_SIZE =
            new Policy(List.of(new DataClassGuard(Map.of("public", List.of("us", "local")))));

    private final GatewayConfig config = config(Map.of(
            "support-bot", new Tenant(List.of("qwen3", "claude-sonnet"), "internal", null, null),
            "analytics", new Tenant(List.of("claude-sonnet"), "public", "any-size", null)));

    private static Provider provider(String region) {
        return new Provider(
                WireFormat.OPENAI, URI.create("https://api.example.com"), null, region, Duration.ofSeconds(30));
    }

    private static GatewayConfig config(Map<String, Tenant> tenants) {
        return new GatewayConfig(
                DATA_CLASSES,
                Map.of("ollama", OLLAMA, "anthropic", ANTHROPIC),
                MODELS,
                tenants,
                List.of(),
                Map.of(GatewayConfig.DEFAULT_POLICY, STRICT, "any-size", ANY_SIZE, "open", Policy.NONE),
                AuditSettings.DISABLED);
    }

    private Admission admit(String tenant, String model, @Nullable String requestedDataClass, long size) {
        return config.admit(
                tenant, config.route(tenant, model).orElseThrow(), requestedDataClass, size, RequestFeatures.NONE);
    }

    @Test
    void routesATenantToItsModels() {
        assertThat(config.route("support-bot", "qwen3"))
                .contains(new GatewayConfig.Route("qwen3", QWEN, "ollama", OLLAMA));
        assertThat(config.route("support-bot", "qwen3").orElseThrow().upstream())
                .isEqualTo("qwen3:32b");
        assertThat(config.routes("support-bot"))
                .extracting(GatewayConfig.Route::model)
                .containsExactly("qwen3", "claude-sonnet");
    }

    @Test
    void hidesModelsTheTenantMayNotUse() {
        assertThat(config.route("support-bot", "llama")).isEmpty();
        assertThat(config.route("support-bot", "gpt-5")).isEmpty();
        assertThat(config.route("billing", "qwen3")).isEmpty();
        assertThat(config.routes("billing")).isEmpty();
    }

    @Test
    void admitsARequestThatPassesEveryGuard() {
        Admission admission = admit("support-bot", "claude-sonnet", null, 500);

        assertThat(admission.admitted()).isTrue();
        assertThat(admission.dataClass()).isEqualTo("internal");
        assertThat(admission.steps())
                .extracting(GuardStep::guard)
                .containsExactly("request-features", "request-size", "data-class");
    }

    @Test
    void appliesAStricterDataClassFromTheRequest() {
        Admission admission = admit("support-bot", "claude-sonnet", "confidential", 500);

        assertThat(admission.admitted()).isFalse();
        assertThat(admission.dataClass()).isEqualTo("confidential");
        assertThat(admission.decision())
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED,
                        "Data class 'confidential' may go only to regions [local]; the model 'claude-sonnet' is in"
                                + " region 'us'"));
        assertThat(admit("support-bot", "qwen3", "confidential", 500).admitted())
                .isTrue();
    }

    @Test
    void acceptsTheTenantsOwnDataClassFromTheRequest() {
        assertThat(admit("support-bot", "claude-sonnet", "internal", 500).admitted())
                .isTrue();
    }

    @Test
    void neverLetsARequestLoosenItsDataClass() {
        Admission admission = admit("support-bot", "qwen3", "public", 500);

        assertThat(admission.decision())
                .isEqualTo(Decision.deny(
                        Refusal.POLICY_DENIED, "Data class 'public' is less strict than this tenant's 'internal'"));
        assertThat(admission.steps()).isEmpty();
    }

    @Test
    void rejectsUnknownDataClasses() {
        Admission admission = admit("support-bot", "qwen3", "secret", 500);

        assertThat(admission.decision())
                .isEqualTo(Decision.deny(
                        Refusal.INVALID_DATA_CLASS,
                        "Unknown data class 'secret'; this gateway defines [public, internal, confidential]"));
    }

    @Test
    void stopsAtTheFirstGuardThatDenies() {
        Admission admission = admit("support-bot", "qwen3", null, 1_001);

        assertThat(admission.decision())
                .isEqualTo(Decision.deny(Refusal.TOO_LARGE, "The request is 1001 bytes; this policy allows 1000"));
        assertThat(admission.steps()).hasSize(2);
    }

    @Test
    void checksRequestFeaturesBeforeThePolicysGuards() {
        var features = new RequestFeatures(List.of("The field 'mcp_servers' is not allowed"), List.of(), Set.of());

        Admission admission =
                config.admit("support-bot", config.route("support-bot", "qwen3").orElseThrow(), null, 1_001, features);

        assertThat(admission.decision())
                .isEqualTo(Decision.deny(Refusal.POLICY_DENIED, "The field 'mcp_servers' is not allowed"));
        assertThat(admission.steps()).extracting(GuardStep::guard).containsExactly("request-features");
    }

    @Test
    void usesTheTenantsOwnPolicyWhenItNamesOne() {
        assertThat(admit("analytics", "claude-sonnet", null, 1_000_000).admitted())
                .isTrue();
    }

    @Test
    void checksOnlyRequestFeaturesWhenThereIsNoDefaultPolicy() {
        var open = new GatewayConfig(
                List.of(),
                Map.of("ollama", OLLAMA),
                Map.of("qwen3", QWEN),
                Map.of("support-bot", new Tenant(List.of("qwen3"), null, null, null)),
                List.of(),
                Map.of(),
                AuditSettings.DISABLED);
        var route = open.route("support-bot", "qwen3").orElseThrow();

        Admission admission = open.admit("support-bot", route, null, Long.MAX_VALUE, RequestFeatures.NONE);

        assertThat(admission.admitted()).isTrue();
        assertThat(admission.dataClass()).isNull();
        assertThat(admission.steps()).extracting(GuardStep::guard).containsExactly("request-features");
    }

    @Test
    void requiresEveryTenantToHaveAKnownDataClassOnceClassesExist() {
        assertThatThrownBy(() -> config(Map.of("support-bot", new Tenant(List.of(), null, null, null))))
                .hasMessage("tenants.support-bot.dataClass is required because dataClasses are defined");
        assertThatThrownBy(() -> config(Map.of("support-bot", new Tenant(List.of(), "secret", null, null))))
                .hasMessage("tenants.support-bot.dataClass: no data class named 'secret'");
    }

    @Test
    void refusesATenantWhosePolicyCannotEnforceItsDataClass() {
        assertThatThrownBy(() -> config(Map.of("support-bot", new Tenant(List.of(), "internal", "open", null))))
                .hasMessage("tenants.support-bot: policy 'open' has no data-class guard, so data class 'internal'"
                        + " would not be enforced");
        assertThatThrownBy(() -> new GatewayConfig(
                        DATA_CLASSES,
                        Map.of(),
                        Map.of(),
                        Map.of("support-bot", new Tenant(List.of(), "internal", null, null)),
                        List.of(),
                        Map.of(),
                        AuditSettings.DISABLED))
                .hasMessage("tenants.support-bot has no policy to enforce its data class; set"
                        + " tenants.support-bot.policy or define a 'default' policy");
    }

    @Test
    void checksTheDataClassesAFeatureNames() {
        var policies = Map.of("p", new Policy(List.of(), Map.of(Feature.REMOTE_IMAGES, List.of("secret"))));

        assertThatThrownBy(() -> new GatewayConfig(
                        DATA_CLASSES, Map.of(), Map.of(), Map.of(), List.of(), policies, AuditSettings.DISABLED))
                .hasMessage("policies.p.features.remote-images: no data class named 'secret'");
    }

    @Test
    void appliesThePolicyFeaturesToTheRequest() {
        var images = new RequestFeatures(List.of(), List.of(), Set.of(Feature.REMOTE_IMAGES));
        var withImages = new Policy(STRICT.guards(), Map.of(Feature.REMOTE_IMAGES, List.of("internal")));
        var allowing = new GatewayConfig(
                DATA_CLASSES,
                Map.of("ollama", OLLAMA, "anthropic", ANTHROPIC),
                MODELS,
                Map.of("support-bot", new Tenant(List.of("qwen3"), "internal", null, null)),
                List.of(),
                Map.of(GatewayConfig.DEFAULT_POLICY, withImages),
                AuditSettings.DISABLED);
        var route = allowing.route("support-bot", "qwen3").orElseThrow();
        var strictRoute = config.route("support-bot", "qwen3").orElseThrow();

        assertThat(allowing.admit("support-bot", route, null, 10, images).admitted())
                .isTrue();
        assertThat(allowing.admit("support-bot", route, "confidential", 10, images)
                        .admitted())
                .isFalse();
        assertThat(config.admit("support-bot", strictRoute, null, 10, images).admitted())
                .isFalse();
    }

    @Test
    void requiresAPriceForEveryAnthropicModelThatMaySearch() {
        var searching = new Policy(STRICT.guards(), Map.of(Feature.WEB_SEARCH, List.of("internal")));
        var tenants = Map.of("support-bot", new Tenant(List.of("qwen3", "claude-sonnet"), "internal", null, null));
        var anthropicFormat = new Provider(
                WireFormat.ANTHROPIC, URI.create("https://api.example.com"), null, "us", Duration.ofSeconds(30));

        assertThatThrownBy(() -> new GatewayConfig(
                        DATA_CLASSES,
                        Map.of("ollama", OLLAMA, "anthropic", anthropicFormat),
                        MODELS,
                        tenants,
                        List.of(),
                        Map.of(GatewayConfig.DEFAULT_POLICY, searching),
                        AuditSettings.DISABLED))
                .hasMessage("tenants.support-bot may use web search, so models.claude-sonnet needs price.webSearch"
                        + " and maxWebSearches");
        assertThat(new GatewayConfig(
                                DATA_CLASSES,
                                Map.of("ollama", OLLAMA, "anthropic", ANTHROPIC),
                                MODELS,
                                tenants,
                                List.of(),
                                Map.of(GatewayConfig.DEFAULT_POLICY, searching),
                                AuditSettings.DISABLED)
                        .tenants())
                .containsKey("support-bot");
    }

    @Test
    void offersWebSearchOnlyWithAPriceAndALimit() {
        var priced = Prices.perMillion("0", "0", "0", "0");
        var withSearch = new ModelPrice(
                priced.input(),
                priced.output(),
                priced.cacheRead(),
                priced.cacheWrite(),
                new SearchPrice(priced.input().perMillionTokens()));

        assertThat(new Model("p", "u", withSearch, null, 2).offersWebSearch()).isTrue();
        assertThat(new Model("p", "u", withSearch, null, 0).offersWebSearch()).isFalse();
        assertThat(new Model("p", "u", priced, null, 2).offersWebSearch()).isFalse();
        assertThatThrownBy(() -> new Model("p", "u", priced, null, -1))
                .hasMessage("maxWebSearches must not be negative");
    }

    @Test
    void requiresNamedPoliciesToExist() {
        assertThatThrownBy(() -> config(Map.of("support-bot", new Tenant(List.of(), "public", "lenient", null))))
                .hasMessage("tenants.support-bot.policy: no policy named 'lenient'");
    }

    @Test
    void checksTheDataClassesAGuardNames() {
        var guard = new DataClassGuard(Map.of("secret", List.of("local")));
        var policies = Map.of("p", new Policy(List.of(guard)));
        assertThatThrownBy(() -> new GatewayConfig(
                        DATA_CLASSES, Map.of(), Map.of(), Map.of(), List.of(), policies, AuditSettings.DISABLED))
                .hasMessage("policies.p: the data-class guard names an unknown data class 'secret'");
        var badName = List.of("top secret");
        assertThatThrownBy(() -> new GatewayConfig(
                        badName, Map.of(), Map.of(), Map.of(), List.of(), Map.of(), AuditSettings.DISABLED))
                .hasMessageStartingWith("data class 'top secret' must start with a letter or digit");
        var repeated = List.of("public", "public");
        assertThatThrownBy(() -> new GatewayConfig(
                        repeated, Map.of(), Map.of(), Map.of(), List.of(), Map.of(), AuditSettings.DISABLED))
                .hasMessage("dataClasses must not repeat a data class");
    }

    @Test
    void requiresAnOutputCapOnEveryModelOfATenantWithABudget() {
        var budget = new Budget(Prices.usd("10"), Period.MONTHLY);
        var tenants = Map.of("support-bot", new Tenant(List.of("qwen3", "llama"), "public", null, budget));

        assertThatThrownBy(() -> config(tenants))
                .hasMessage("tenants.support-bot.budget needs models.llama.maxOutputTokens, so that the most a call"
                        + " can cost is known");
    }

    @Test
    void isImmutable() {
        assertThat(config.tenants().values())
                .allSatisfy(tenant -> assertThat(tenant.models()).isUnmodifiable());
        assertThat(config.models()).isUnmodifiable();
        assertThat(config.dataClasses()).isUnmodifiable();
    }
}
