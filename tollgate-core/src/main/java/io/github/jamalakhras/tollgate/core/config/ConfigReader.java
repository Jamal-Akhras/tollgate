package io.github.jamalakhras.tollgate.core.config;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.limit.Limits;
import io.github.jamalakhras.tollgate.core.money.ContextTier;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.PriceCatalog;
import io.github.jamalakhras.tollgate.core.money.SearchPrice;
import io.github.jamalakhras.tollgate.core.money.TokenPrice;
import io.github.jamalakhras.tollgate.core.money.TokenRates;
import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.Guard;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.RequestSizeGuard;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.time.Duration;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public final class ConfigReader {

    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);
    static final Currency DEFAULT_CURRENCY = Currency.getInstance("USD");

    private ConfigReader() {}

    public static GatewayConfig read(Map<String, ?> document) {
        return read(document, PriceCatalog.bundled());
    }

    public static GatewayConfig read(Map<String, ?> document, PriceCatalog catalog) {
        Section root = Section.root(document);
        root.requireOnly(
                "currency", "dataClasses", "providers", "models", "tenants", "keys", "detectors", "policies", "audit");
        Currency currency = root.currency("currency", DEFAULT_CURRENCY);
        var dataClasses = root.texts("dataClasses");
        var providers = root.sections("providers", section -> provider(section, catalog));
        var models = root.sections("models", section -> model(section, currency, providers, catalog));
        var tenants = root.sections("tenants", section -> tenant(section, currency));
        var keys = root.sectionList("keys", ConfigReader::key);
        var detectors = detectors(root);
        var policies = root.sections("policies", section -> policy(section, detectors));
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

    private static Provider provider(Section section, PriceCatalog catalog) {
        section.requireOnly("format", "baseUrl", "apiKey", "region", "timeout", "extraFields", "catalog");
        String apiKey = section.optionalText("apiKey");
        String named = section.optionalText("catalog");
        if (named != null && !catalog.covers(named)) {
            throw section.invalid("catalog", "names no price catalog: " + named);
        }
        return section.build(() -> new Provider(
                WireFormat.fromConfigName(section.text("format")),
                section.uri("baseUrl"),
                apiKey == null ? null : SecretRef.parse(apiKey),
                section.optionalText("region"),
                section.duration("timeout", DEFAULT_TIMEOUT),
                section.texts("extraFields"),
                named));
    }

    private static Model model(
            Section section, Currency currency, Map<String, Provider> providers, PriceCatalog catalog) {
        section.requireOnly("provider", "upstream", "price", "maxOutputTokens", "maxWebSearches");
        String provider = section.text("provider");
        String upstream = section.text("upstream");
        String priceCatalog =
                section.has("price") ? null : priceCatalog(section, currency, providers.get(provider), provider);
        ModelPrice price = priceCatalog == null
                ? writtenPrice(section, currency)
                : catalog.price(priceCatalog, upstream)
                        .orElseThrow(() -> new InvalidConfigException(section.path("price") + " is required: the "
                                + priceCatalog + " price catalog has no model " + upstream));
        OutputTokens maxOutputTokens = section.optionalChild("maxOutputTokens", ConfigReader::outputTokens);
        long maxWebSearches = section.optionalWholeNumber("maxWebSearches", 0);
        return section.build(() -> new Model(provider, upstream, price, maxOutputTokens, maxWebSearches, priceCatalog));
    }

    private static String priceCatalog(
            Section section, Currency currency, @Nullable Provider provider, String providerName) {
        if (provider == null) {
            throw new InvalidConfigException(section.path("provider") + ": no provider named '" + providerName + "'");
        }
        String required = section.path("price") + " is required";
        if (!currency.equals(DEFAULT_CURRENCY)) {
            throw new InvalidConfigException(
                    required + ", because the price catalog is in USD and the currency is " + currency);
        }
        return provider.priceCatalog()
                .orElseThrow(() -> new InvalidConfigException(required
                        + ": its provider has no price catalog. Write the price, or price: free if it costs nothing"));
    }

    private static ModelPrice writtenPrice(Section section, Currency currency) {
        if (section.isText("price")) {
            String text = section.text("price");
            if (!text.equals("free")) {
                throw section.invalid("price", "must be a price or free, was " + text);
            }
            return ModelPrice.free(currency);
        }
        return section.child("price", child -> price(child, currency));
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
        section.requireOnly("models", "dataClass", "policy", "budget", "showCosts", "limits");
        Budget budget = section.optionalChild("budget", child -> budget(child, currency));
        Limits limits = section.optionalChild("limits", ConfigReader::limits);
        return section.build(() -> new Tenant(
                section.texts("models"),
                section.optionalText("dataClass"),
                section.optionalText("policy"),
                budget,
                section.flag("showCosts", false),
                limits == null ? Limits.NONE : limits));
    }

    private static Limits limits(Section section) {
        section.requireOnly("requestsPerMinute", "tokensPerMinute", "concurrent");
        return section.build(() -> new Limits(
                section.optionalWholeNumber("requestsPerMinute"),
                section.optionalWholeNumber("tokensPerMinute"),
                section.optionalWholeNumber("concurrent")));
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

    private static Map<String, Detector> detectors(Section root) {
        Map<String, Detector> detectors = root.namedSections("detectors", ConfigReader::detector);
        Map<String, String> labels = new HashMap<>();
        Detector.BUILT_IN.forEach(detector -> labels.put(detector.label(), detector.configName()));
        detectors.forEach((name, detector) -> {
            String clash = labels.putIfAbsent(detector.label(), name);
            if (clash != null) {
                throw new InvalidConfigException(root.path("detectors") + "." + name + ": its placeholders, such as ["
                        + detector.label() + "_1], would be the same as those of detector '" + clash + "'");
            }
        });
        return detectors;
    }

    private static Detector detector(String name, Section section) {
        section.requireOnly("pattern", "terms", "caseSensitive");
        if (Detector.builtIn(name).isPresent()) {
            throw section.invalid("'" + name + "' is a built-in detector; choose another name");
        }
        boolean pattern = section.has("pattern");
        if (pattern == section.has("terms")) {
            throw section.invalid("set either pattern or terms");
        }
        if (pattern && section.has("caseSensitive")) {
            throw section.invalid("caseSensitive applies only to terms; start a pattern with (?i) to ignore case");
        }
        String regex = pattern ? section.text("pattern") : "";
        List<String> terms = pattern ? List.of() : section.texts("terms");
        boolean caseSensitive = section.flag("caseSensitive", false);
        return section.build(() -> {
            Names.check("detector", name);
            return pattern ? Detector.pattern(name, regex) : Detector.terms(name, terms, caseSensitive);
        });
    }

    private static Policy policy(Section section, Map<String, Detector> detectors) {
        section.requireOnly("guards", "features", "redact", "refuse");
        List<Guard> guards = section.sectionList("guards", ConfigReader::guard);
        Map<String, List<String>> features = section.optionalTextLists("features");
        var redact = byDetector(section, "redact", detectors);
        var refuse = byDetector(section, "refuse", detectors);
        return section.build(() -> new Policy(guards, byFeature(features), redact, refuse));
    }

    private static Map<Detector, List<String>> byDetector(
            Section section, String key, Map<String, Detector> detectors) {
        Map<Detector, List<String>> byDetector = new HashMap<>();
        section.optionalTextLists(key).forEach((name, dataClasses) -> {
            Detector detector = detectors.get(name);
            if (detector == null) {
                detector = Detector.builtIn(name)
                        .orElseThrow(() -> section.invalid(
                                key,
                                "names no detector '" + name + "'; define it under detectors, or use a built-in"
                                        + " one: " + Detector.builtInNames()));
            }
            byDetector.put(detector, dataClasses);
        });
        return byDetector;
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
