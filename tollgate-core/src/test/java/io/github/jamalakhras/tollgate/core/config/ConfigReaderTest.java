package io.github.jamalakhras.tollgate.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.Period;
import io.github.jamalakhras.tollgate.core.limit.Limits;
import io.github.jamalakhras.tollgate.core.money.ModelPrice;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.PriceCatalog;
import io.github.jamalakhras.tollgate.core.money.Prices;
import io.github.jamalakhras.tollgate.core.money.TokenRates;
import io.github.jamalakhras.tollgate.core.policy.DataClassGuard;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.Policy;
import io.github.jamalakhras.tollgate.core.policy.RequestSizeGuard;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class ConfigReaderTest {

    private static final String KEY_HASH = "a".repeat(64);
    private static final Map<String, Object> FREE = Map.of("input", 0, "output", 0, "cacheRead", 0, "cacheWrite", 0);
    private static final Map<String, Object> CLAUDE_PRICE =
            Map.of("input", 3, "output", 15L, "cacheRead", new BigDecimal("0.30"), "cacheWrite", 3.75);
    private static final Map<String, Object> SIZE_GUARD = Map.of("type", "request-size", "maxBytes", 1_048_576);
    private static final Map<String, Object> DATA_CLASS_GUARD = Map.of(
            "type",
            "data-class",
            "regions",
            Map.of("public", List.of("us", "local"), "confidential", List.of("local")));

    private static final Map<String, Object> AUDIT = Map.of(
            "mode", "sync",
            "directory", "audit",
            "publicKey", "MCowBQYDK2VwAyEA",
            "signingKey", "${env:TOLLGATE_AUDIT_SIGNING_KEY}",
            "hmacKey", "${env:TOLLGATE_AUDIT_HMAC_KEY}");

    private static final PriceCatalog CATALOG = Prices.catalog("""
            anthropic\tclaude-sonnet-4-5\t0\t3\t15\t15\t0.3\t3.75
            openrouter\tqwen/qwen3-32b\t0\t0.1\t0.3\t0.3\t0.1\t0.1
            """);

    private static Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("dataClasses", List.of("public", "confidential"));
        document.put(
                "providers",
                Map.of(
                        "anthropic",
                                Map.of(
                                        "format", "anthropic",
                                        "baseUrl", "https://api.anthropic.com/",
                                        "apiKey", "${env:ANTHROPIC_API_KEY}",
                                        "region", "us",
                                        "timeout", "30s"),
                        "ollama", Map.of("format", "openai", "baseUrl", "http://gpu-box:11434/v1", "region", "local")));
        document.put(
                "models",
                Map.of(
                        "claude-sonnet",
                        Map.of(
                                "provider",
                                "anthropic",
                                "upstream",
                                "claude-sonnet-4-5",
                                "price",
                                CLAUDE_PRICE,
                                "maxOutputTokens",
                                Map.of("default", 2_048, "cap", 8_192)),
                        "qwen3",
                        Map.of("provider", "ollama", "upstream", "qwen3:32b", "price", FREE)));
        document.put(
                "tenants",
                Map.of(
                        "support-bot",
                        Map.of("models", List.of("qwen3", "claude-sonnet"), "dataClass", "confidential")));
        document.put("keys", List.of(Map.of("id", "k7Qm2xPa9bCd", "tenant", "support-bot", "sha256", KEY_HASH)));
        document.put("policies", Map.of("default", Map.of("guards", List.of(SIZE_GUARD, DATA_CLASS_GUARD))));
        document.put("audit", AUDIT);
        return document;
    }

    private static Map<String, Object> with(String path, Object value) {
        Map<String, Object> document = deepCopy(document());
        parentOf(document, path).put(lastPart(path), value);
        return document;
    }

    private static Map<String, Object> without(String path) {
        Map<String, Object> document = deepCopy(document());
        parentOf(document, path).remove(lastPart(path));
        return document;
    }

    private static Map<String, Object> parentOf(Map<String, Object> document, String path) {
        Map<String, Object> parent = document;
        int start = 0;
        for (int dot = path.indexOf('.'); dot >= 0; dot = path.indexOf('.', start)) {
            parent = cast(Objects.requireNonNull(parent.get(path.substring(start, dot))));
            start = dot + 1;
        }
        return parent;
    }

    private static String lastPart(String path) {
        return path.substring(path.lastIndexOf('.') + 1);
    }

    private static <T> T entry(Map<String, T> map, String key) {
        return Objects.requireNonNull(map.get(key), key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>();
        source.forEach((key, value) -> copy.put(key, value instanceof Map<?, ?> ? deepCopy(cast(value)) : value));
        return copy;
    }

    @Test
    void readsAWholeDocument() {
        GatewayConfig config = ConfigReader.read(document());

        Provider anthropic = entry(config.providers(), "anthropic");
        assertThat(anthropic.format()).isEqualTo(WireFormat.ANTHROPIC);
        assertThat(anthropic.baseUrl()).isEqualTo(URI.create("https://api.anthropic.com"));
        assertThat(anthropic.apiKey()).isEqualTo(new SecretRef("ANTHROPIC_API_KEY"));
        assertThat(anthropic.timeout()).isEqualTo(Duration.ofSeconds(30));

        Provider ollama = entry(config.providers(), "ollama");
        assertThat(ollama.apiKey()).isNull();
        assertThat(ollama.timeout()).isEqualTo(ConfigReader.DEFAULT_TIMEOUT);

        Model claude = entry(config.models(), "claude-sonnet");
        assertThat(claude.price())
                .isEqualTo(Prices.perMillion("3", "15", "0.30", "3.75").withCacheWrite1hAtTwiceInput());
        assertThat(claude.maxOutputTokens()).isEqualTo(new OutputTokens(2_048, 8_192));
        assertThat(entry(config.models(), "qwen3").maxOutputTokens()).isNull();
        assertThat(entry(config.tenants(), "support-bot").models()).containsExactly("qwen3", "claude-sonnet");
        assertThat(config.keys()).containsExactly(new TenantKey("k7Qm2xPa9bCd", "support-bot", KEY_HASH));
        assertThat(config.dataClasses()).containsExactly("public", "confidential");
        assertThat(anthropic.region()).isEqualTo("us");
        assertThat(entry(config.tenants(), "support-bot").dataClass()).isEqualTo("confidential");
        assertThat(entry(config.policies(), "default").guards())
                .containsExactly(
                        new RequestSizeGuard(1_048_576),
                        new DataClassGuard(Map.of("public", List.of("us", "local"), "confidential", List.of("local"))));
    }

    @Test
    void readsBudgetsInTheConfiguredCurrency() {
        Map<String, Object> document = with("currency", "GBP");
        parentOf(document, "tenants.support-bot.x").put("budget", Map.of("limit", 50, "period", "monthly"));
        parentOf(document, "models.qwen3.x").put("maxOutputTokens", Map.of("default", 1_024, "cap", 1_024));

        GatewayConfig config = ConfigReader.read(document);

        assertThat(entry(config.tenants(), "support-bot").budget())
                .isEqualTo(new Budget(Money.of(new BigDecimal(50), Currency.getInstance("GBP")), Period.MONTHLY));
        assertThat(entry(config.models(), "claude-sonnet").price().currency()).isEqualTo(Currency.getInstance("GBP"));
    }

    @Test
    void readsTenantLimits() {
        GatewayConfig config = ConfigReader.read(with(
                "tenants.support-bot.limits",
                Map.of("requestsPerMinute", 60, "tokensPerMinute", 200_000, "concurrent", 8)));

        assertThat(entry(config.tenants(), "support-bot").limits()).isEqualTo(new Limits(60L, 200_000L, 8L));
        assertThat(entry(
                                ConfigReader.read(with("tenants.support-bot.limits", Map.of("concurrent", 4)))
                                        .tenants(),
                                "support-bot")
                        .limits())
                .isEqualTo(new Limits(null, null, 4L));
        assertThat(entry(ConfigReader.read(document()).tenants(), "support-bot").limits())
                .isEqualTo(Limits.NONE);
    }

    @Test
    void reportsLimitProblems() {
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.limits", Map.of("concurrent", 0))))
                .hasMessage("tenants.support-bot.limits: concurrent must be positive, was 0");
        assertThatThrownBy(() ->
                        ConfigReader.read(with("tenants.support-bot.limits", Map.of("requestsPerMinute", "lots"))))
                .hasMessage("tenants.support-bot.limits.requestsPerMinute must be a whole number, was lots");
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.limits", Map.of("perHour", 10))))
                .hasMessage("tenants.support-bot.limits.perHour is not a recognised setting");
    }

    @Test
    void reportsMoneyProblems() {
        assertThatThrownBy(() -> ConfigReader.read(with("currency", "dollars")))
                .hasMessage("currency must be an ISO 4217 currency code, was dollars");
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.price", Map.of("input", 1))))
                .hasMessage("models.qwen3.price.output is required");
        assertThatThrownBy(() -> ConfigReader.read(without("models.qwen3.price")))
                .hasMessageStartingWith("models.qwen3.price is required: its provider has no price catalog");
        assertThatThrownBy(() -> ConfigReader.read(with(
                        "models.qwen3.price", Map.of("input", "free", "output", 0, "cacheRead", 0, "cacheWrite", 0))))
                .hasMessage("models.qwen3.price.input must be an amount such as 3.50, was free");
        assertThatThrownBy(() -> ConfigReader.read(with(
                        "models.qwen3.price",
                        Map.of("input", new BigDecimal("0.0000000001"), "output", 0, "cacheRead", 0, "cacheWrite", 0))))
                .hasMessage("models.qwen3.price.input has more than 9 decimal places: 1E-10");
        assertThatThrownBy(() -> ConfigReader.read(
                        with("models.qwen3.price", Map.of("input", -1, "output", 0, "cacheRead", 0, "cacheWrite", 0))))
                .hasMessageStartingWith("models.qwen3.price: Price must not be negative");
        assertThatThrownBy(() ->
                        ConfigReader.read(with("models.qwen3.maxOutputTokens", Map.of("default", 9_000, "cap", 8_192))))
                .hasMessage("models.qwen3.maxOutputTokens: default must not exceed cap");
        assertThatThrownBy(() ->
                        ConfigReader.read(with("tenants.support-bot.budget", Map.of("limit", 5, "period", "weekly"))))
                .hasMessage("tenants.support-bot.budget: period must be one of daily, monthly, total, was weekly");
    }

    @Test
    void rejectsUnknownSettingsInMoneySections() {
        var price = Map.of("input", 0, "output", 0, "cacheRead", 0, "cacheWrite", 0, "batch", 0);
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.price", price)))
                .hasMessage("models.qwen3.price.batch is not a recognised setting");
        var output = Map.of("default", 1, "cap", 1, "minimum", 1);
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.maxOutputTokens", output)))
                .hasMessage("models.qwen3.maxOutputTokens.minimum is not a recognised setting");
        var budget = Map.of("limit", 5, "period", "daily", "onExceed", "block");
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.budget", budget)))
                .hasMessage("tenants.support-bot.budget.onExceed is not a recognised setting");
    }

    @Test
    void rejectsUnknownSettingsInKeys() {
        Map<String, Object> key =
                Map.of("id", "k7Qm2xPa9bCd", "tenant", "support-bot", "sha256", KEY_HASH, "note", "x");
        assertThatThrownBy(() -> ConfigReader.read(with("keys", List.of(key))))
                .hasMessage("keys[0].note is not a recognised setting");
    }

    @Test
    void reportsGuardProblems() {
        assertThatThrownBy(() -> ConfigReader.read(withGuard(Map.of("type", "pii-redact"))))
                .hasMessage("policies.default.guards[0].type must be one of request-size, data-class, was pii-redact");
        assertThatThrownBy(() -> ConfigReader.read(withGuard(Map.of("type", "request-size", "maxBytes", "1MB"))))
                .hasMessage("policies.default.guards[0].maxBytes must be a whole number, was 1MB");
        assertThatThrownBy(() -> ConfigReader.read(withGuard(Map.of("type", "request-size"))))
                .hasMessage("policies.default.guards[0].maxBytes is required");
        assertThatThrownBy(() -> ConfigReader.read(withGuard(Map.of("type", "request-size", "maxBytes", 0))))
                .hasMessage("policies.default.guards[0]: maxBytes must be positive");
        assertThatThrownBy(
                        () -> ConfigReader.read(withGuard(Map.of("type", "request-size", "maxBytes", 5, "mode", "x"))))
                .hasMessage("policies.default.guards[0].mode is not a recognised setting");
        assertThatThrownBy(
                        () -> ConfigReader.read(withGuard(Map.of("type", "data-class", "regions", Map.of(), "x", 1))))
                .hasMessage("policies.default.guards[0].x is not a recognised setting");
        assertThatThrownBy(() -> ConfigReader.read(withGuard(Map.of("type", "data-class"))))
                .hasMessage("policies.default.guards[0].regions is required");
        assertThatThrownBy(() ->
                        ConfigReader.read(withGuard(Map.of("type", "data-class", "regions", Map.of("public", "us")))))
                .hasMessage("policies.default.guards[0].regions.public must be a list");
    }

    @Test
    void readsWebSearchPricesAndLimits() {
        Map<String, Object> price = Map.of("input", 3, "output", 15, "cacheRead", 0, "cacheWrite", 0, "webSearch", 10);
        Map<String, Object> model =
                Map.of("provider", "anthropic", "upstream", "claude", "price", price, "maxWebSearches", 3);

        GatewayConfig config = ConfigReader.read(with("models.searcher", model));

        Model searcher = entry(config.models(), "searcher");
        assertThat(searcher.maxWebSearches()).isEqualTo(3);
        assertThat(searcher.offersWebSearch()).isTrue();
        assertThat(searcher.price().webSearch()).isNotNull();
    }

    @Test
    void pricesAnthropicCacheWritesKeptForAnHourAtTwiceTheInputUnlessWritten() {
        Map<String, Object> written = new HashMap<>(CLAUDE_PRICE);
        written.put("cacheWrite1h", 5);

        TokenRates claude = entry(
                        ConfigReader.read(with("models.claude-sonnet.price", CLAUDE_PRICE))
                                .models(),
                        "claude-sonnet")
                .price()
                .rates();
        TokenRates set = entry(
                        ConfigReader.read(with("models.claude-sonnet.price", written))
                                .models(),
                        "claude-sonnet")
                .price()
                .rates();
        TokenRates qwen = entry(
                        ConfigReader.read(with("models.qwen3.price", CLAUDE_PRICE))
                                .models(),
                        "qwen3")
                .price()
                .rates();

        assertThat(claude.cacheWrite1h().perMillionTokens()).isEqualTo(Prices.usd("6"));
        assertThat(set.cacheWrite1h().perMillionTokens()).isEqualTo(Prices.usd("5"));
        assertThat(qwen.cacheWrite1h().perMillionTokens()).isEqualTo(Prices.usd("3.75"));
    }

    @Test
    void readsReasoningAndLongContextPrices() {
        Map<String, Object> tier = Map.of(
                "fromTokens", 256_000, "input", 1.2, "output", 3.6, "reasoning", 12, "cacheRead", 0, "cacheWrite", 0);
        Map<String, Object> price = Map.of(
                "input",
                0.4,
                "output",
                1.2,
                "reasoning",
                4,
                "cacheRead",
                0,
                "cacheWrite",
                0,
                "longContext",
                List.of(tier));
        Map<String, Object> model = Map.of("provider", "ollama", "upstream", "qwen-plus", "price", price);

        ModelPrice read = entry(
                        ConfigReader.read(with("models.qwen-plus", model)).models(), "qwen-plus")
                .price();

        assertThat(read.rates().reasoning().perMillionTokens()).isEqualTo(Prices.usd("4"));
        assertThat(read.longContext()).singleElement().satisfies(found -> {
            assertThat(found.fromTokens()).isEqualTo(256_000);
            assertThat(found.rates().reasoning().perMillionTokens()).isEqualTo(Prices.usd("12"));
        });

        Map<String, Object> unknown = new HashMap<>(tier);
        unknown.put("batch", 0.5);
        Map<String, Object> withUnknown = new HashMap<>(price);
        withUnknown.put("longContext", List.of(unknown));
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.price", withUnknown)))
                .hasMessage("models.qwen3.price.longContext[0].batch is not a recognised setting");
    }

    @Test
    void showsCostsOnlyToTenantsThatAskForThem() {
        assertThat(entry(ConfigReader.read(document()).tenants(), "support-bot").showCosts())
                .isFalse();
        assertThat(entry(
                                ConfigReader.read(with("tenants.support-bot.showCosts", true))
                                        .tenants(),
                                "support-bot")
                        .showCosts())
                .isTrue();
    }

    @Test
    void chargesReasoningAsOutputUnlessPricedApart() {
        TokenRates rates =
                entry(ConfigReader.read(document()).models(), "qwen3").price().rates();

        assertThat(rates.reasoning()).isEqualTo(rates.output());
    }

    @Test
    void takesAMissingPriceFromTheCatalogOfTheProvidersHost() {
        GatewayConfig config = ConfigReader.read(without("models.claude-sonnet.price"), CATALOG);

        assertThat(entry(config.models(), "claude-sonnet").price())
                .isEqualTo(CATALOG.price("anthropic", "claude-sonnet-4-5")
                        .orElseThrow()
                        .withCacheWrite1hAtTwiceInput());
        assertThat(entry(config.models(), "claude-sonnet").priceCatalog()).isEqualTo("anthropic");
        assertThat(entry(config.models(), "qwen3").priceCatalog()).isNull();
    }

    @Test
    void readsFreeModelsAndCatalogsNamedByTheProvider() {
        Map<String, Object> document = without("models.qwen3.price");
        parentOf(document, "providers.ollama.catalog").put("catalog", "openrouter");
        parentOf(document, "models.qwen3.upstream").put("upstream", "qwen/qwen3-32b");

        assertThat(entry(ConfigReader.read(document, CATALOG).models(), "qwen3").price())
                .isEqualTo(CATALOG.price("openrouter", "qwen/qwen3-32b").orElseThrow());
        assertThat(entry(
                                ConfigReader.read(with("models.qwen3.price", "free"), CATALOG)
                                        .models(),
                                "qwen3")
                        .price())
                .isEqualTo(ModelPrice.free(Currency.getInstance("USD")));
    }

    @Test
    void explainsWhyAModelNeedsAWrittenPrice() {
        assertThatThrownBy(() -> ConfigReader.read(without("models.qwen3.price"), CATALOG))
                .hasMessage("models.qwen3.price is required: its provider has no price catalog."
                        + " Write the price, or price: free if it costs nothing");
        Map<String, Object> unknownModel = without("models.claude-sonnet.price");
        parentOf(unknownModel, "models.claude-sonnet.upstream").put("upstream", "claude-next");
        assertThatThrownBy(() -> ConfigReader.read(unknownModel, CATALOG))
                .hasMessage(
                        "models.claude-sonnet.price is required: the anthropic price catalog has no model claude-next");
        Map<String, Object> pounds = without("models.claude-sonnet.price");
        pounds.put("currency", "GBP");
        assertThatThrownBy(() -> ConfigReader.read(pounds, CATALOG))
                .hasMessage("models.claude-sonnet.price is required, because the price catalog is in USD and the"
                        + " currency is GBP");
        Map<String, Object> unknownProvider = without("models.qwen3.price");
        parentOf(unknownProvider, "models.qwen3.provider").put("provider", "vllm");
        assertThatThrownBy(() -> ConfigReader.read(unknownProvider, CATALOG))
                .hasMessage("models.qwen3.provider: no provider named 'vllm'");
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.price", "cheap"), CATALOG))
                .hasMessage("models.qwen3.price must be a price or free, was cheap");
        assertThatThrownBy(() -> ConfigReader.read(with("providers.ollama.catalog", "nowhere"), CATALOG))
                .hasMessage("providers.ollama.catalog names no price catalog: nowhere");
    }

    @Test
    void readsAProvidersExtraFields() {
        Map<String, Object> local = Map.of(
                "format", "openai", "baseUrl", "http://gpu-box:11434/v1", "extraFields", List.of("top_k", "num_ctx"));

        GatewayConfig config = ConfigReader.read(with("providers.ollama", local));

        assertThat(entry(config.providers(), "ollama").extraFields()).containsExactly("top_k", "num_ctx");
        assertThatThrownBy(() -> ConfigReader.read(with(
                        "providers.ollama",
                        Map.of(
                                "format",
                                "openai",
                                "baseUrl",
                                "http://gpu-box:11434/v1",
                                "extraFields",
                                List.of("store")))))
                .hasMessage("providers.ollama: extraFields must not include 'store': it keeps the conversation at the"
                        + " provider");
    }

    @Test
    void readsAnOutputFloor() {
        GatewayConfig config = ConfigReader.read(
                with("models.claude-sonnet.maxOutputTokens", Map.of("default", 2_048, "cap", 8_192, "floor", 512)));

        assertThat(entry(config.models(), "claude-sonnet").maxOutputTokens())
                .isEqualTo(new OutputTokens(2_048, 8_192, 512));
    }

    @Test
    void readsPolicyFeatures() {
        Map<String, Object> features = Map.of("remote-images", List.of("public"), "unreviewed-fields", List.of());
        GatewayConfig config = ConfigReader.read(
                with("policies", Map.of("default", Map.of("guards", List.of(DATA_CLASS_GUARD), "features", features))));

        assertThat(entry(config.policies(), "default").features())
                .containsOnly(
                        Map.entry(Feature.REMOTE_IMAGES, List.of("public")),
                        Map.entry(Feature.UNREVIEWED_FIELDS, List.of()));
    }

    @Test
    void readsWhatAPolicyRedactsAndRefuses() {
        GatewayConfig config = ConfigReader.read(with(
                "policies",
                Map.of(
                        "default",
                        Map.of(
                                "guards", List.of(DATA_CLASS_GUARD),
                                "redact", Map.of("email", List.of("confidential"), "national-id", List.of()),
                                "refuse", Map.of("secret", List.of("public", "confidential"))))));

        Policy policy = entry(config.policies(), "default");
        assertThat(policy.redact())
                .containsOnly(
                        Map.entry(Detector.EMAIL, List.of("confidential")), Map.entry(Detector.NATIONAL_ID, List.of()));
        assertThat(policy.refuse()).containsOnly(Map.entry(Detector.SECRET, List.of("public", "confidential")));
    }

    @Test
    void rejectsUnknownDetectors() {
        Map<String, Object> unknown =
                Map.of("guards", List.of(DATA_CLASS_GUARD), "redact", Map.of("names", List.of("public")));
        Map<String, Object> notAList = Map.of("guards", List.of(DATA_CLASS_GUARD), "refuse", Map.of("secret", true));

        assertThatThrownBy(() -> ConfigReader.read(with("policies", Map.of("default", unknown))))
                .hasMessage("policies.default.redact names no detector 'names'; define it under detectors, or use a"
                        + " built-in one: secret, email, card, iban, national-id, phone, ip");
        assertThatThrownBy(() -> ConfigReader.read(with("policies", Map.of("default", notAList))))
                .hasMessageStartingWith("policies.default.refuse.secret");
    }

    private static Map<String, Object> withDetectors(Map<String, Object> detectors, Map<String, Object> redact) {
        Map<String, Object> document = with("detectors", detectors);
        document.put("policies", Map.of("default", Map.of("guards", List.of(DATA_CLASS_GUARD), "redact", redact)));
        return document;
    }

    @Test
    void readsDetectorsTheConfigDefinesAndUsesThemInPolicies() {
        GatewayConfig config = ConfigReader.read(withDetectors(
                Map.of(
                        "customer-id", Map.of("pattern", "CUST-[0-9]{6}"),
                        "clients", Map.of("terms", List.of("Globex", "Acme Holdings"), "caseSensitive", true)),
                Map.of("customer-id", List.of("confidential"), "clients", List.of("public"), "email", List.of())));

        Policy policy = entry(config.policies(), "default");
        assertThat(policy.redacts("confidential"))
                .extracting(Detector::configName)
                .containsExactly("customer-id");
        assertThat(policy.redacts("public")).extracting(Detector::configName).containsExactly("clients");
        Redactor redactor = new Redactor(Set.of(), policy.redacts("public"));
        assertThat(redactor.redact("Globex and globex")).isEqualTo("[CLIENTS_1] and globex");
    }

    static Stream<Arguments> brokenDetectors() {
        return Stream.of(
                Arguments.of(
                        Map.of("email", Map.of("pattern", "x")),
                        "detectors.email: 'email' is a built-in detector; choose another name"),
                Arguments.of(
                        Map.of("both", Map.of("pattern", "x", "terms", List.of("x"))),
                        "detectors.both: set either pattern or terms"),
                Arguments.of(Map.of("neither", Map.of()), "detectors.neither: set either pattern or terms"),
                Arguments.of(
                        Map.of("cased", Map.of("pattern", "x", "caseSensitive", true)),
                        "detectors.cased: caseSensitive applies only to terms; start a pattern with (?i) to ignore case"),
                Arguments.of(
                        Map.of("bad", Map.of("pattern", "x(")),
                        "detectors.bad: pattern is not a valid regular expression: Unclosed group near index 2"),
                Arguments.of(
                        Map.of("empty", Map.of("terms", List.of())),
                        "detectors.empty: terms must list at least one term"),
                Arguments.of(
                        Map.of("-dash", Map.of("pattern", "x")),
                        "detectors.-dash: detector '-dash' must start with a letter or digit and use only letters,"
                                + " digits, '.', '_', ':' and '-'"),
                Arguments.of(
                        Map.of("odd", Map.of("pattern", "x", "flags", "i")),
                        "detectors.odd.flags is not a recognised setting"),
                Arguments.of(
                        Map.of("national.id", Map.of("pattern", "x")),
                        "detectors.national.id: its placeholders, such as [NATIONAL_ID_1], would be the same as"
                                + " those of detector 'national-id'"));
    }

    @ParameterizedTest
    @MethodSource("brokenDetectors")
    void explainsWhatIsWrongWithADetector(Map<String, Object> detectors, String problem) {
        assertThatThrownBy(() -> ConfigReader.read(withDetectors(detectors, Map.of())))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage(problem);
    }

    @Test
    void rejectsTwoDetectorsWithTheSamePlaceholders() {
        Map<String, Object> detectors = new LinkedHashMap<>();
        detectors.put("customer-id", Map.of("pattern", "x"));
        detectors.put("customer_id", Map.of("pattern", "y"));

        assertThatThrownBy(() -> ConfigReader.read(withDetectors(detectors, Map.of())))
                .hasMessage("detectors.customer_id: its placeholders, such as [CUSTOMER_ID_1], would be the same as"
                        + " those of detector 'customer-id'");
    }

    @Test
    void rejectsUnknownFeatures() {
        Map<String, Object> unknown =
                Map.of("guards", List.of(DATA_CLASS_GUARD), "features", Map.of("web-fetch", List.of()));
        Map<String, Object> notAList =
                Map.of("guards", List.of(DATA_CLASS_GUARD), "features", Map.of("remote-images", "public"));

        assertThatThrownBy(() -> ConfigReader.read(with("policies", Map.of("default", unknown))))
                .hasMessage(
                        "policies.default: feature must be one of remote-images, remote-documents, provider-files, web-search,"
                                + " unreviewed-fields, was web-fetch");
        assertThatThrownBy(() -> ConfigReader.read(with("policies", Map.of("default", notAList))))
                .hasMessageStartingWith("policies.default.features.remote-images");
    }

    @Test
    void readsLargeByteLimits() {
        GatewayConfig config = ConfigReader.read(withGuard(Map.of("type", "request-size", "maxBytes", 5_000_000_000L)));
        assertThat(entry(config.policies(), "default").guards())
                .first()
                .isEqualTo(new RequestSizeGuard(5_000_000_000L));
    }

    private static Map<String, Object> withGuard(Map<String, Object> guard) {
        return with("policies", Map.of("default", Map.of("guards", List.of(guard, DATA_CLASS_GUARD))));
    }

    @Test
    void treatsMissingSectionsAsEmpty() {
        GatewayConfig config = ConfigReader.read(Map.of("audit", Map.of("mode", "off")));
        assertThat(config.providers()).isEmpty();
        assertThat(config.keys()).isEmpty();
        assertThat(config.audit()).isEqualTo(AuditSettings.DISABLED);
    }

    @Test
    void readsTheAuditSettings() {
        assertThat(ConfigReader.read(document()).audit())
                .isEqualTo(new AuditSettings.Enabled(
                        true,
                        "audit",
                        "MCowBQYDK2VwAyEA",
                        new SecretRef("TOLLGATE_AUDIT_SIGNING_KEY"),
                        new SecretRef("TOLLGATE_AUDIT_HMAC_KEY")));
        assertThat(ConfigReader.read(with("audit.mode", "async")).audit())
                .isInstanceOfSatisfying(
                        AuditSettings.Enabled.class,
                        audit -> assertThat(audit.synchronous()).isFalse());
    }

    @Test
    void requiresTheAuditToBeConfiguredOrTurnedOffExplicitly() {
        assertThatThrownBy(() -> ConfigReader.read(without("audit"))).hasMessage("audit is required");
        assertThatThrownBy(() -> ConfigReader.read(without("audit.mode"))).hasMessage("audit.mode is required");
        assertThatThrownBy(() -> ConfigReader.read(with("audit.mode", "never")))
                .hasMessage("audit.mode must be one of sync, async, off, was never");
        assertThatThrownBy(() -> ConfigReader.read(with("audit.mode", "off")))
                .hasMessageStartingWith("audit.")
                .hasMessageEndingWith(" is not a recognised setting");
        assertThatThrownBy(() -> ConfigReader.read(without("audit.hmacKey"))).hasMessage("audit.hmacKey is required");
        assertThatThrownBy(() -> ConfigReader.read(with("audit.path", "audit")))
                .hasMessage("audit.path is not a recognised setting");
        assertThatThrownBy(() -> ConfigReader.read(with("audit.signingKey", "MC4CAQAwBQYDK2VwBCIEI")))
                .hasMessage("audit: secrets must be references such as ${env:PROVIDER_API_KEY}, never values");
    }

    @Test
    void readsAModelsFallbacks() {
        Map<String, Object> document = with("models.qwen3.fallback", List.of("backup"));
        parentOf(document, "models.backup")
                .put("backup", Map.of("provider", "ollama", "upstream", "qwen3:8b", "price", "free"));
        GatewayConfig config = ConfigReader.read(document);

        assertThat(entry(config.models(), "qwen3").fallback()).containsExactly("backup");
        assertThat(entry(ConfigReader.read(document()).models(), "qwen3").fallback())
                .isEmpty();
        assertThatThrownBy(() -> ConfigReader.read(with("models.qwen3.fallback", List.of("gpt-5"))))
                .hasMessage("models.qwen3.fallback: no model named 'gpt-5'");
    }

    @Test
    void readsAProvidersConcurrentLimit() {
        GatewayConfig config = ConfigReader.read(with("providers.ollama.concurrent", 2));

        assertThat(entry(config.providers(), "ollama").concurrent()).isEqualTo(2L);
        assertThat(entry(ConfigReader.read(document()).providers(), "ollama").concurrent())
                .isNull();
        assertThatThrownBy(() -> ConfigReader.read(with("providers.ollama.concurrent", 0)))
                .hasMessage("providers.ollama: concurrent must be positive, was 0");
    }

    @ParameterizedTest
    @CsvSource({"500ms, PT0.5S", "45s, PT45S", "2m, PT2M"})
    void readsDurations(String value, Duration expected) {
        GatewayConfig config = ConfigReader.read(with("providers.ollama.timeout", value));
        assertThat(entry(config.providers(), "ollama").timeout()).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            provders                         | x               | provders is not a recognised setting
            providers.ollama.zone            | eu              | providers.ollama.zone is not a recognised setting
            models.qwen3.region              | eu              | models.qwen3.region is not a recognised setting
            tenants.support-bot.quota        | 50              | tenants.support-bot.quota is not a recognised setting
            policies.default.mode            | monitor         | policies.default.mode is not a recognised setting
            providers.ollama.format          | grpc            | providers.ollama: format must be one of openai, anthropic, was grpc
            providers.ollama.baseUrl         | not a url       | providers.ollama.baseUrl is not a valid URL: not a url
            providers.ollama.baseUrl         | ftp://host/v1   | providers.ollama: baseUrl must be an absolute http or https URL, was ftp://host/v1
            providers.ollama.timeout         | 10 seconds      | providers.ollama.timeout must be a duration such as 500ms, 30s or 2m, was 10 seconds
            providers.ollama.timeout         | 0s              | providers.ollama: timeout must be positive
            providers.ollama.concurrent      | two             | providers.ollama.concurrent must be a whole number, was two
            providers.anthropic.apiKey       | sk-ant-12345    | providers.anthropic: secrets must be references such as ${env:PROVIDER_API_KEY}, never values
            providers.ollama.apiKey          | ${env:OLLAMA}   | providers.ollama: an API key may only be sent over https, except to this machine
            models.qwen3.provider            | vllm            | models.qwen3.provider: no provider named 'vllm'
            models.qwen3.upstream            | ' '             | models.qwen3.upstream must be a non-empty string
            models.qwen3.price.reasoning     | free            | models.qwen3.price.reasoning must be an amount such as 3.50, was free
            tenants.support-bot.showCosts    | yes             | tenants.support-bot.showCosts must be true or false, was yes
            models.qwen3.price.longContext   | 128000          | models.qwen3.price.longContext must be a list
            """)
    void reportsWhereAProblemIs(String path, String value, String message) {
        assertThatThrownBy(() -> ConfigReader.read(with(path, value)))
                .isInstanceOf(InvalidConfigException.class)
                .hasMessage(message);
    }

    @Test
    void reportsMissingRequiredSettings() {
        assertThatThrownBy(() -> ConfigReader.read(without("providers.ollama.format")))
                .hasMessage("providers.ollama.format is required");
        assertThatThrownBy(() -> ConfigReader.read(without("models.qwen3.upstream")))
                .hasMessage("models.qwen3.upstream is required");
    }

    @Test
    void reportsValuesOfTheWrongShape() {
        assertThatThrownBy(() -> ConfigReader.read(with("providers", List.of())))
                .hasMessage("providers must be a mapping");
        assertThatThrownBy(() -> ConfigReader.read(with("providers.ollama", "openai")))
                .hasMessage("providers.ollama must be a mapping");
        assertThatThrownBy(() -> ConfigReader.read(with("providers.ollama.format", 7)))
                .hasMessage("providers.ollama.format must be a non-empty string");
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.models", "qwen3")))
                .hasMessage("tenants.support-bot.models must be a list");
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.models", List.of("qwen3", 4))))
                .hasMessage("tenants.support-bot.models[1] must be a non-empty string");
        assertThatThrownBy(() -> ConfigReader.read(with("keys", List.of("tg_key"))))
                .hasMessage("keys[0] must be a mapping");
        assertThatThrownBy(() -> ConfigReader.read(with("models", Map.of(1, Map.of()))))
                .hasMessage("models has a key that is not a string: 1");
    }

    @Test
    void checksReferencesBetweenSections() {
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.models", List.of("gpt-5"))))
                .hasMessage("tenants.support-bot.models: no model named 'gpt-5'");
        assertThatThrownBy(() -> ConfigReader.read(with("tenants.support-bot.models", List.of("qwen3", "qwen3"))))
                .hasMessage("tenants.support-bot: models must not repeat a model");
        assertThatThrownBy(() -> ConfigReader.read(
                        with("keys", List.of(Map.of("id", "k7Qm2xPa9bCd", "tenant", "billing", "sha256", KEY_HASH)))))
                .hasMessage("keys: key k7Qm2xPa9bCd belongs to an unknown tenant 'billing'");
        Map<String, Object> key = Map.of("id", "k7Qm2xPa9bCd", "tenant", "support-bot", "sha256", KEY_HASH);
        assertThatThrownBy(() -> ConfigReader.read(with("keys", List.of(key, key))))
                .hasMessage("keys: key ID k7Qm2xPa9bCd appears more than once");
    }

    @Test
    void checksKeyEntries() {
        assertThatThrownBy(() -> ConfigReader.read(
                        with("keys", List.of(Map.of("id", "short", "tenant", "support-bot", "sha256", KEY_HASH)))))
                .hasMessage("keys[0]: id must be the 12-character key ID printed by 'tollgate keys create'");
        assertThatThrownBy(() -> ConfigReader.read(with(
                        "keys",
                        List.of(Map.of("id", "k7Qm2xPa9bCd", "tenant", "support-bot", "sha256", "A".repeat(64))))))
                .hasMessage("keys[0]: sha256 must be 64 lowercase hex characters");
    }

    @Test
    void checksNames() {
        Map<String, Map<String, Object>> validEntries = Map.of(
                "provider", Map.of("format", "openai", "baseUrl", "http://gpu-box:11434/v1"),
                "model", Map.of("provider", "ollama", "upstream", "qwen3", "price", FREE),
                "tenant", Map.of("models", List.of()),
                "policy", Map.of("guards", List.of()));
        validEntries.forEach((kind, entry) -> assertThatThrownBy(
                        () -> ConfigReader.read(with(kind.replaceAll("y$", "ie") + "s", Map.of("bad name", entry))))
                .hasMessageStartingWith(kind + " 'bad name' must start with a letter or digit"));
    }
}
