package io.github.jamalakhras.tollgate.core.budget;

import io.github.jamalakhras.tollgate.core.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryBudgetStore implements BudgetStore {

    private final Clock clock;
    private final ConcurrentMap<String, Ledger> ledgers = new ConcurrentHashMap<>();

    public InMemoryBudgetStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<Reservation> reserve(String tenant, Budget budget, Money amount) {
        Ledger ledger = ledgers.computeIfAbsent(tenant, name -> new Ledger());
        if (!ledger.reserve(budget, amount.nanos(), clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(new Reservation(tenant, budget, amount));
    }

    @Override
    public void settle(Reservation reservation, Money cost) {
        Ledger ledger = ledgers.get(reservation.tenant());
        if (ledger == null) {
            throw new IllegalStateException("No reservation was made for " + reservation.tenant());
        }
        ledger.settle(reservation, cost.nanos(), clock.instant());
    }

    @Override
    public Money available(String tenant, Budget budget) {
        Ledger ledger = ledgers.get(tenant);
        long nanos = ledger == null ? budget.limit().nanos() : ledger.available(budget, clock.instant());
        return new Money(nanos, budget.limit().currency());
    }

    @Override
    public void restore(String tenant, Budget budget, Money cost, Instant settledAt) {
        ledgers.computeIfAbsent(tenant, name -> new Ledger()).restore(budget, cost.nanos(), settledAt, clock.instant());
    }

    @Override
    public Money spent(String tenant, Budget budget) {
        Ledger ledger = ledgers.get(tenant);
        long nanos = ledger == null ? 0 : ledger.spent(budget, clock.instant());
        return new Money(nanos, budget.limit().currency());
    }

    @Override
    public Money reserved(String tenant, Budget budget) {
        Ledger ledger = ledgers.get(tenant);
        long nanos = ledger == null ? 0 : ledger.reserved();
        return new Money(nanos, budget.limit().currency());
    }

    private static final class Ledger {

        private Instant periodStart = Instant.MIN;
        private long spent;
        private long reserved;

        synchronized boolean reserve(Budget budget, long amount, Instant now) {
            enterPeriod(budget, now);
            long committed = Math.addExact(Math.addExact(spent, reserved), amount);
            if (committed > budget.limit().nanos()) {
                return false;
            }
            reserved = Math.addExact(reserved, amount);
            return true;
        }

        synchronized void settle(Reservation reservation, long cost, Instant now) {
            enterPeriod(reservation.budget(), now);
            reserved = Math.subtractExact(reserved, reservation.amount().nanos());
            spent = Math.addExact(spent, cost);
        }

        synchronized void restore(Budget budget, long cost, Instant settledAt, Instant now) {
            enterPeriod(budget, now);
            if (budget.period().start(settledAt).equals(periodStart)) {
                spent = Math.addExact(spent, cost);
            }
        }

        synchronized long available(Budget budget, Instant now) {
            enterPeriod(budget, now);
            return Math.max(0, budget.limit().nanos() - spent - reserved);
        }

        synchronized long reserved() {
            return reserved;
        }

        synchronized long spent(Budget budget, Instant now) {
            enterPeriod(budget, now);
            return spent;
        }

        private void enterPeriod(Budget budget, Instant now) {
            Instant start = budget.period().start(now);
            if (!start.equals(periodStart)) {
                periodStart = start;
                spent = 0;
            }
        }
    }
}
