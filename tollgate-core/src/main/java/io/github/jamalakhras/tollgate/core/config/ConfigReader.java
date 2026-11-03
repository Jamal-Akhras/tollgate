package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.money.ContextTier;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.SearchPrice;
import io.github.jamalakhras.tollgate.core.money.TokenPrice;
import io.github.jamalakhras.tollgate.core.money.TokenRates;
import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.Guard;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.RequestSizeGuard;
import java.time.Duration;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class ConfigReader {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    static final Currency DEFAULT_CURRENCY = Currency.getInstance("USD");

    private ConfigReader() {}

    public static GatewayConfig read(Map<String, ?> document) {
        Section root = Section.root(document);
        root.requireOnly("currency", "dataClasses", "providers", "models", "tenants", "keys", "policies", "audit");
        Currency currency = root.currency("currency", DEFAULT_CURRENCY);
        var dataClasses = root.texts("dataClasses");
        var providers = root.sections("providers", ConfigReader::provider);
        var models = root.sections("models", section -> model(section, currency));
        var tenants = root.sections("tenants", section -> tenant(section, currency));
        var keys = root.sectionList("keys", ConfigReader::key);
        var policies = root.sections("policies", ConfigReader::policy);
        var audit = root.child("audit", ConfigReader::audit);
        return root.build(() -> new GatewayConfig(dataClasses, providers, models, tenants, keys, policies, audit));
    }

    private static AuditSettings audit(Section section) {
        String mode = section.text("mode");
        return switch (mode) {
            case "off" -> {
                section.requireOnly("mode");
                yield AuditSettings.DISABLED;
            }
            case "sync", "async" -> {
                section.requireOnly("mode", "directory", "publicKey", "signingKey", "hmacKey");
                yield section.build(() -> new AuditSettings.Enabled(
                        mode.equals("sync"),
                        section.text("directory"),
                        section.text("publicKey"),
                        SecretRef.parse(section.text("signingKey")),
                        SecretRef.parse(section.text("hmacKey"))));
            }
            default -> throw section.invalid("mode", "must be one of sync, async, off, was " + mode);
        };
    }

    private static Provider provider(Section section) {
        section.requireOnly("format", "baseUrl", "apiKey", "region", "timeout", "extraFields");
        String apiKey = section.optionalText("apiKey");
        return section.build(() -> new Provider(
                WireFormat.fromConfigName(section.text("format")),
                section.uri("baseUrl"),
                apiKey == null ? null : SecretRef.parse(apiKey),
                section.optionalText("region"),
                section.duration("timeout", DEFAULT_TIMEOUT),
                section.texts("extraFields")));
    }

    private static Model model(Section section, Currency currency) {
        section.requireOnly("provider", "upstream", "price", "maxOutputTokens", "maxWebSearches");
        ModelPrice price = section.child("price", child -> price(child, currency));
        OutputTokens maxOutputTokens = section.optionalChild("maxOutputTokens", ConfigReader::outputTokens);
        long maxWebSearches = section.optionalWholeNumber("maxWebSearches", 0);
        return section.build(() ->
                new Model(section.text("provider"), section.text("upstream"), price, maxOutputTokens, maxWebSearches));
    }

    private static ModelPrice price(Section section, Currency currency) {
        section.requireOnly("input", "output", "reasoning", "cacheRead", "cacheWrite", "webSearch", "longContext");
        TokenRates rates = rates(section, currency);
        List<ContextTier> longContext = section.sectionList("longContext", tier -> contextTier(tier, currency));
        Money webSearch = section.optionalMoney("webSearch", currency);
        return section.build(
                () -> new ModelPrice(rates, longContext, webSearch == null ? null : new SearchPrice(webSearch)));
    }

    private static ContextTier contextTier(Section section, Currency currency) {
        section.requireOnly("fromTokens", "input", "output", "reasoning", "cacheRead", "cacheWrite");
        TokenRates rates = rates(section, currency);
        return section.build(() -> new ContextTier(section.wholeNumber("fromTokens"), rates));
    }

    private static TokenRates rates(Section section, Currency currency) {
        Money output = section.money("output", currency);
        Money reasoning = section.optionalMoney("reasoning", currency);
        return section.build(() -> new TokenRates(
                new TokenPrice(section.money("input", currency)),
                new TokenPrice(output),
                new TokenPrice(reasoning == null ? output : reasoning),
                new TokenPrice(section.money("cacheRead", currency)),
                new TokenPrice(section.money("cacheWrite", currency))));
    }

    private static OutputTokens outputTokens(Section section) {
        section.requireOnly("default", "cap", "floor");
        long cap = section.wholeNumber("cap");
        long floor = section.optionalWholeNumber("floor", Math.min(OutputTokens.DEFAULT_FLOOR, cap));
        return section.build(() -> new OutputTokens(section.wholeNumber("default"), cap, floor));
    }

    private static Tenant tenant(Section section, Currency currency) {
        section.requireOnly("models", "dataClass", "policy", "budget");
        Budget budget = section.optionalChild("budget", child -> budget(child, currency));
        return section.build(() -> new Tenant(
                section.texts("models"), section.optionalText("dataClass"), section.optionalText("policy"), budget));
    }

    private static Budget budget(Section section, Currency currency) {
        section.requireOnly("limit", "period");
        return section.build(
                () -> new Budget(section.money("limit", currency), Period.fromConfigName(section.text("period"))));
    }

    private static TenantKey key(Section section) {
        section.requireOnly("id", "tenant", "sha256");
        return section.build(() -> new TenantKey(section.text("id"), section.text("tenant"), section.text("sha256")));
    }

    private static Policy policy(Section section) {
        section.requireOnly("guards", "features");
        List<Guard> guards = section.sectionList("guards", ConfigReader::guard);
        Map<String, List<String>> features = section.optionalTextLists("features");
        return section.build(() -> new Policy(guards, byFeature(features)));
    }

    private static Map<Feature, List<String>> byFeature(Map<String, List<String>> features) {
        Map<Feature, List<String>> byFeature = new EnumMap<>(Feature.class);
        features.forEach((name, dataClasses) -> byFeature.put(Feature.fromConfigName(name), dataClasses));
        return byFeature;
    }

    private static Guard guard(Section section) {
        String type = section.text("type");
        return switch (type) {
            case RequestSizeGuard.TYPE -> {
                section.requireOnly("type", "maxBytes");
                yield section.build(() -> new RequestSizeGuard(section.wholeNumber("maxBytes")));
            }
            case DataClassGuard.TYPE -> {
                section.requireOnly("type", "regions");
                yield section.build(() -> new DataClassGuard(section.textLists("regions")));
            }
            default ->
                throw section.invalid(
                        "type",
                        "must be one of " + RequestSizeGuard.TYPE + ", " + DataClassGuard.TYPE + ", was " + type);
        };
    }
}
