package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.config.AuditSettings;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.Model;
import io.github.jamalakhras.tollgate.core.config.Provider;
import io.github.jamalakhras.tollgate.core.config.SecretRef;
import io.github.jamalakhras.tollgate.core.config.Tenant;
import io.github.jamalakhras.tollgate.core.config.TenantKey;
import io.github.jamalakhras.tollgate.core.limit.Limits;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Guard;
import io.github.jamalakhras.tollgate.core.policy.MonitoredGuard;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.RequestSizeGuard;
import io.github.jamalakhras.tollgate.core.policy.ToolNamesGuard;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Config", description = "The configuration this instance is running")
@RequestMapping(path = "/admin/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class ConfigViewController {

    private final Gateway gateway;
    private final ConfigEditor editor;

    ConfigViewController(Gateway gateway, ConfigEditor editor) {
        this.gateway = gateway;
        this.editor = editor;
    }

    @GetMapping("/config")
    @Operation(
            summary = "Show the running config",
            description = "The config this instance is running, as a view and not the file: secrets are shown as the"
                    + " variable that holds them, and key hashes, detector patterns and detector terms are left out.")
    @ApiResponse(responseCode = "200", description = "The running config")
    ConfigView config() {
        return view(gateway.configVersion(), editor.on(), editor.rewritten(), gateway.config());
    }

    static ConfigView view(String version, boolean managed, boolean rewritten, GatewayConfig config) {
        return new ConfigView(
                version,
                managed ? "managed" : "read-only",
                rewritten,
                config.dataClasses(),
                sorted(config.providers(), ConfigViewController::provider),
                sorted(config.models(), ConfigViewController::model),
                sorted(config.tenants(), ConfigViewController::tenant),
                config.keys().stream().map(ConfigViewController::key).toList(),
                sorted(config.policies(), ConfigViewController::policy),
                audit(config.audit()));
    }

    private static <T, V> List<V> sorted(Map<String, T> byName, NamedView<T, V> view) {
        return new TreeMap<>(byName)
                .entrySet().stream()
                        .map(entry -> view.of(entry.getKey(), entry.getValue()))
                        .toList();
    }

    private static ProviderView provider(String name, Provider provider) {
        SecretRef apiKey = provider.apiKey();
        return new ProviderView(
                name,
                provider.format().configName(),
                provider.baseUrl().getAuthority(),
                provider.region(),
                provider.concurrent(),
                apiKey == null ? null : apiKey.toString());
    }

    private static ModelView model(String name, Model model) {
        ModelPrice price = model.price();
        return new ModelView(
                name,
                model.provider(),
                model.upstream(),
                price.equals(ModelPrice.free(price.currency())),
                model.maxOutputTokens() == null ? null : model.maxOutputTokens().cap(),
                model.fallback());
    }

    private static TenantView tenant(String name, Tenant tenant) {
        var budget = tenant.budget();
        String policy = tenant.policy();
        Limits limits = tenant.limits();
        return new TenantView(
                name,
                tenant.dataClass(),
                policy == null ? GatewayConfig.DEFAULT_POLICY : policy,
                tenant.models(),
                budget == null
                        ? null
                        : new BudgetView(
                                budget.limit()
                                        .toBigDecimal()
                                        .stripTrailingZeros()
                                        .toPlainString(),
                                budget.limit().currency().getCurrencyCode(),
                                budget.period().configName()),
                new LimitsView(limits.requestsPerMinute(), limits.tokensPerMinute(), limits.concurrent()),
                tenant.userHeader() != null,
                tenant.showCosts());
    }

    private static KeyView key(TenantKey key) {
        return new KeyView(key.id(), key.tenant());
    }

    private static PolicyView policy(String name, Policy policy) {
        return new PolicyView(
                name,
                policy.guards().stream().map(ConfigViewController::guard).toList(),
                named(policy.features(), feature -> feature.configName()),
                named(policy.redact(), detector -> detector.configName()),
                named(policy.refuse(), detector -> detector.configName()));
    }

    private static <K> Map<String, List<String>> named(Map<K, List<String>> byKey, Function<K, String> name) {
        Map<String, List<String>> named = new TreeMap<>();
        byKey.forEach((key, dataClasses) -> named.put(name.apply(key), dataClasses));
        return named;
    }

    private static GuardView guard(Guard guard) {
        String mode = guard instanceof MonitoredGuard ? "monitor" : "enforce";
        Guard underlying = MonitoredGuard.underlying(guard);
        Long maxBytes = null;
        Map<String, List<String>> regions = null;
        List<String> allow = null;
        List<String> refuse = null;
        if (underlying instanceof RequestSizeGuard size) {
            maxBytes = size.maxBytes();
        } else if (underlying instanceof DataClassGuard dataClass) {
            regions = new TreeMap<>(dataClass.regions());
        } else if (underlying instanceof ToolNamesGuard toolNames) {
            allow = toolNames.allow();
            refuse = toolNames.refuse();
        }
        return new GuardView(guard.type(), mode, maxBytes, regions, allow, refuse);
    }

    private static AuditView audit(AuditSettings audit) {
        if (audit instanceof AuditSettings.Enabled enabled) {
            return new AuditView(enabled.synchronous() ? "sync" : "async", enabled.publicKey());
        }
        return new AuditView("off", null);
    }

    @FunctionalInterface
    private interface NamedView<T, V> {

        V of(String name, T value);
    }

    record ConfigView(
            String version,
            String edits,
            boolean rewritten,
            List<String> dataClasses,
            List<ProviderView> providers,
            List<ModelView> models,
            List<TenantView> tenants,
            List<KeyView> keys,
            List<PolicyView> policies,
            AuditView audit) {}

    record ProviderView(
            String name,
            String format,
            String host,
            @Nullable String region,
            @Nullable Long concurrent,
            @Nullable String apiKey) {}

    record ModelView(
            String name,
            String provider,
            String upstream,
            boolean free,
            @Nullable Long outputCap,
            List<String> fallback) {}

    record TenantView(
            String name,
            @Nullable String dataClass,
            String policy,
            List<String> models,
            @Nullable BudgetView budget,
            LimitsView limits,
            boolean attributesUsers,
            boolean showsCosts) {}

    record BudgetView(String limit, String currency, String period) {}

    record LimitsView(
            @Nullable Long requestsPerMinute,
            @Nullable Long tokensPerMinute,
            @Nullable Long concurrent) {}

    record KeyView(String id, String tenant) {}

    record PolicyView(
            String name,
            List<GuardView> guards,
            Map<String, List<String>> features,
            Map<String, List<String>> redact,
            Map<String, List<String>> refuse) {}

    record GuardView(
            String type,
            String mode,
            @Nullable Long maxBytes,
            @Nullable Map<String, List<String>> regions,
            @Nullable List<String> allow,
            @Nullable List<String> refuse) {}

    record AuditView(String mode, @Nullable String publicKey) {}
}
