package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.BudgetStore;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.Tenant;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.server.audit.AuditDirectory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class BudgetReplay {

    private final GatewayConfig config;
    private final BudgetStore budgets;
    private final Map<String, Intent> open = new LinkedHashMap<>();
    private int settled;

    private BudgetReplay(GatewayConfig config, BudgetStore budgets) {
        this.config = config;
        this.budgets = budgets;
    }

    record Replayed(int settled, int unfinished) {}

    static Replayed replay(Path directory, GatewayConfig config, BudgetStore budgets) throws IOException {
        BudgetReplay replay = new BudgetReplay(config, budgets);
        AuditDirectory.records(directory, record -> {
            switch (record) {
                case Intent intent -> replay.intent(intent);
                case Outcome outcome -> replay.outcome(outcome);
            }
        });
        replay.open.values().forEach(intent -> replay.charge(intent.tenant(), intent.reservation(), intent.time()));
        return new Replayed(replay.settled, replay.open.size());
    }

    private void intent(Intent intent) {
        if (intent.decision() instanceof Decision.Allow && intent.reservation() != null) {
            open.put(intent.id(), intent);
        }
    }

    private void outcome(Outcome outcome) {
        Intent intent = open.remove(outcome.id());
        if (intent != null) {
            charge(intent.tenant(), outcome.cost(), outcome.time());
            settled++;
        }
    }

    private void charge(String tenantName, @Nullable Money cost, Instant at) {
        Tenant tenant = config.tenants().get(tenantName);
        Budget budget = tenant == null ? null : tenant.budget();
        if (budget != null
                && cost != null
                && cost.currency().equals(budget.limit().currency())) {
            budgets.restore(tenantName, budget, cost, at);
        }
    }
}
