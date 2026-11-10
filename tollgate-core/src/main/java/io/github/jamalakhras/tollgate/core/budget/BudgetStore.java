package io.github.jamalakhras.tollgate.core.budget;

import io.github.jamalakhras.tollgate.core.money.Money;
import java.time.Instant;
import java.util.Optional;

public interface BudgetStore {

    Optional<Reservation> reserve(String tenant, Budget budget, Money amount);

    void settle(Reservation reservation, Money cost);

    Money available(String tenant, Budget budget);

    Money spent(String tenant, Budget budget);

    Money reserved(String tenant, Budget budget);

    void restore(String tenant, Budget budget, Money cost, Instant settledAt);
}
