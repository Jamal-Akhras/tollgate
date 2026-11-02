package io.github.jamalakhras.tollgate.core.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Prices;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InMemoryBudgetStoreTest {

    private static final Budget TEN_DOLLARS_A_MONTH = new Budget(Prices.usd("10"), Period.MONTHLY);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-29T12:00:00Z"));
    private final InMemoryBudgetStore store = new InMemoryBudgetStore(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    private Reservation reserve(String amount) {
        return store.reserve("support-bot", TEN_DOLLARS_A_MONTH, Prices.usd(amount))
                .orElseThrow();
    }

    @Test
    void reservesUpToTheLimit() {
        reserve("6");
        reserve("4");

        assertThat(store.reserve(
                        "support-bot",
                        TEN_DOLLARS_A_MONTH,
                        new Money(1, Prices.usd("0").currency())))
                .isEmpty();
    }

    @Test
    void settlingReplacesTheReservationWithTheActualCost() {
        Reservation reservation = reserve("8");

        store.settle(reservation, Prices.usd("1.25"));

        assertThat(store.spent("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("1.25"));
        assertThat(store.reserve("support-bot", TEN_DOLLARS_A_MONTH, Prices.usd("8.75")))
                .isPresent();
    }

    @Test
    void releasingAReservationFreesItsAmount() {
        store.settle(reserve("10"), Prices.usd("0"));

        assertThat(store.reserve("support-bot", TEN_DOLLARS_A_MONTH, Prices.usd("10")))
                .isPresent();
    }

    @Test
    void keepsTenantsApart() {
        reserve("10");

        assertThat(store.reserve("billing", TEN_DOLLARS_A_MONTH, Prices.usd("10")))
                .isPresent();
        assertThat(store.spent("analytics", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("0"));
    }

    @Test
    void reportsWhatIsLeftAfterSpendAndReservations() {
        assertThat(store.available("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("10"));
        Reservation reservation = reserve("3");
        assertThat(store.available("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("7"));

        store.settle(reservation, Prices.usd("12"));

        assertThat(store.available("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("0"));
        now.set(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(store.available("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("10"));
    }

    @Test
    void startsAgainEachPeriod() {
        store.settle(reserve("9"), Prices.usd("9"));

        now.set(Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(store.spent("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("0"));
        assertThat(store.reserve("support-bot", TEN_DOLLARS_A_MONTH, Prices.usd("10")))
                .isPresent();
    }

    @Test
    void reservesAgainstTheNewPeriodWithoutBeingAskedForSpend() {
        store.settle(reserve("9"), Prices.usd("9"));

        now.set(Instant.parse("2026-11-01T00:00:00Z"));

        assertThat(store.reserve("support-bot", TEN_DOLLARS_A_MONTH, Prices.usd("10")))
                .isPresent();
    }

    @Test
    void chargesACallThatEndsInTheNextPeriodToThatPeriod() {
        Reservation reservation = reserve("5");

        now.set(Instant.parse("2026-11-01T00:00:01Z"));
        store.settle(reservation, Prices.usd("2"));

        assertThat(store.spent("support-bot", TEN_DOLLARS_A_MONTH)).isEqualTo(Prices.usd("2"));
    }

    @Test
    void neverReservesMoreThanTheLimitUnderContention() throws Exception {
        Budget oneDollar = new Budget(Prices.usd("1"), Period.TOTAL);
        List<Future<Optional<Reservation>>> attempts = new ArrayList<>();
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 1_000; i++) {
                attempts.add(threads.submit(() -> store.reserve("support-bot", oneDollar, Prices.usd("0.01"))));
            }
        }
        long granted = 0;
        for (Future<Optional<Reservation>> attempt : attempts) {
            granted += attempt.get().isPresent() ? 1 : 0;
        }

        assertThat(granted).isEqualTo(100);
    }

    @Test
    void refusesToSettleAnUnknownReservation() {
        var stranger = new Reservation("billing", TEN_DOLLARS_A_MONTH, Prices.usd("1"));

        assertThatThrownBy(() -> store.settle(stranger, Prices.usd("1"))).isInstanceOf(IllegalStateException.class);
    }
}
