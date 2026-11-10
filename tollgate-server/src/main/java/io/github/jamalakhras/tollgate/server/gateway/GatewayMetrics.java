package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.BudgetStore;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig.Route;
import io.github.jamalakhras.tollgate.core.limit.Concurrency;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class GatewayMetrics {

    static final String REQUESTS = "tollgate.requests";
    static final String TOKENS = "gen_ai.client.token.usage";
    static final String COST = "tollgate.cost";
    static final String DURATION = "gen_ai.client.operation.duration";
    static final String FIRST_EVENT = "tollgate.stream.first_event";
    static final String BUDGET_SPENT = "tollgate.budget.spent";
    static final String BUDGET_AVAILABLE = "tollgate.budget.available";
    static final String IN_FLIGHT = "tollgate.providers.in_flight";

    private final MeterRegistry registry;
    private final MultiGauge spent;
    private final MultiGauge available;
    private final MultiGauge inFlight;

    GatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.spent = MultiGauge.builder(BUDGET_SPENT).register(registry);
        this.available = MultiGauge.builder(BUDGET_AVAILABLE).register(registry);
        this.inFlight = MultiGauge.builder(IN_FLIGHT).register(registry);
    }

    void refused(String tenant, Route route, String code) {
        registry.counter(REQUESTS, call(tenant, route).and("result", code)).increment();
    }

    void finished(
            String tenant, Route route, Outcome.Result result, Optional<Usage> usage, Money cost, Duration duration) {
        Tags call = call(tenant, route);
        registry.counter(REQUESTS, call.and("result", result.code())).increment();
        if (result == Outcome.Result.DRY_RUN) {
            return;
        }
        usage.ifPresent(used -> {
            tokens(call, "input", used.inputTokens());
            tokens(call, "output", used.outputTokens());
            tokens(call, "cache_read", used.cacheReadTokens());
            tokens(call, "cache_write", used.cacheWriteTokens());
        });
        registry.counter(
                        COST,
                        Tags.of(
                                "tenant",
                                tenant,
                                "model",
                                route.model(),
                                "currency",
                                cost.currency().getCurrencyCode()))
                .increment(amount(cost));
        Timer.builder(DURATION)
                .tags("model", route.model(), "provider", route.providerName(), "result", result.code())
                .register(registry)
                .record(duration);
    }

    void firstEvent(Route route, Duration waited) {
        Timer.builder(FIRST_EVENT)
                .tags("model", route.model(), "provider", route.providerName())
                .register(registry)
                .record(waited);
    }

    void watch(GatewayConfig config, BudgetStore budgets, Concurrency calls) {
        List<MultiGauge.Row<?>> spentRows = new ArrayList<>();
        List<MultiGauge.Row<?>> availableRows = new ArrayList<>();
        config.tenants().forEach((name, tenant) -> {
            Budget budget = tenant.budget();
            if (budget != null) {
                Tags tags = Tags.of(
                        "tenant", name, "currency", budget.limit().currency().getCurrencyCode());
                spentRows.add(MultiGauge.Row.of(tags, budgets, store -> amount(store.spent(name, budget))));
                availableRows.add(MultiGauge.Row.of(tags, budgets, store -> amount(store.available(name, budget))));
            }
        });
        spent.register(spentRows, true);
        available.register(availableRows, true);
        inFlight.register(
                config.providers().keySet().stream()
                        .<MultiGauge.Row<?>>map(name ->
                                MultiGauge.Row.of(Tags.of("provider", name), calls, counts -> counts.inFlight(name)))
                        .toList(),
                true);
    }

    private void tokens(Tags call, String type, long count) {
        DistributionSummary.builder(TOKENS)
                .tags(call.and("gen_ai.token.type", type))
                .register(registry)
                .record((double) count);
    }

    private static Tags call(String tenant, Route route) {
        return Tags.of("tenant", tenant, "model", route.model(), "provider", route.providerName());
    }

    private static double amount(Money money) {
        return money.toBigDecimal().doubleValue();
    }
}
