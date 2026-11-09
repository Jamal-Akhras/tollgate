package io.github.jamalakhras.tollgate.core.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jamalakhras.tollgate.core.limit.RateLimiter.Limit;
import io.github.jamalakhras.tollgate.core.limit.RateLimiter.Limited;
import io.github.jamalakhras.tollgate.core.limit.RateLimiter.Permit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final RateLimiter limiter = new RateLimiter(now::get);

    private void wait(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    private Permit admitted(String tenant, Limits limits) {
        RateLimiter.Result result = limiter.admit(tenant, limits);
        assertThat(result).isInstanceOf(Permit.class);
        return (Permit) result;
    }

    private Limited limited(String tenant, Limits limits) {
        RateLimiter.Result result = limiter.admit(tenant, limits);
        assertThat(result).isInstanceOf(Limited.class);
        return (Limited) result;
    }

    @Test
    void letsAQuietTenantSendAFullMinutesBurstAndThenRefills() {
        Limits limits = new Limits(60L, null, null);
        for (int i = 0; i < 60; i++) {
            admitted("support-bot", limits).close();
        }

        Limited limited = limited("support-bot", limits);
        assertThat(limited.limit()).isEqualTo(Limit.REQUESTS_PER_MINUTE);
        assertThat(limited.value()).isEqualTo(60);
        assertThat(limited.retryAfterSeconds()).hasValue(1);
        assertThat(limited.message()).isEqualTo("This tenant is limited to 60 requests a minute");

        wait(Duration.ofSeconds(1));
        admitted("support-bot", limits).close();
        limited("support-bot", limits);
    }

    @Test
    void saysHowLongUntilTheNextRequestFits() {
        Limits limits = new Limits(2L, null, null);
        admitted("support-bot", limits).close();
        admitted("support-bot", limits).close();

        assertThat(limited("support-bot", limits).retryAfterSeconds()).hasValue(30);
        wait(Duration.ofSeconds(29));
        assertThat(limited("support-bot", limits).retryAfterSeconds()).hasValue(1);
        wait(Duration.ofSeconds(1));
        admitted("support-bot", limits).close();
    }

    @Test
    void neverStoresMoreThanAMinutesAllowance() {
        Limits limits = new Limits(3L, null, null);
        admitted("support-bot", limits).close();
        wait(Duration.ofHours(1));

        for (int i = 0; i < 3; i++) {
            admitted("support-bot", limits).close();
        }
        limited("support-bot", limits);
    }

    @Test
    void chargesTokensAfterTheCallAndRefusesWhileTheBucketIsEmpty() {
        Limits limits = new Limits(null, 1_000L, null);
        Permit first = admitted("support-bot", limits);
        Permit second = admitted("support-bot", limits);
        first.charge(800);
        second.charge(500);
        first.close();
        second.close();

        Limited limited = limited("support-bot", limits);
        assertThat(limited.limit()).isEqualTo(Limit.TOKENS_PER_MINUTE);
        assertThat(limited.message()).isEqualTo("This tenant is limited to 1000 tokens a minute");
        assertThat(limited.retryAfterSeconds()).hasValue(18);

        wait(Duration.ofSeconds(18));
        admitted("support-bot", limits).close();
    }

    @Test
    void countsCallsInFlightUntilTheyClose() {
        Limits limits = new Limits(null, null, 2L);
        Permit first = admitted("support-bot", limits);
        Permit second = admitted("support-bot", limits);

        Limited limited = limited("support-bot", limits);
        assertThat(limited.limit()).isEqualTo(Limit.CONCURRENT);
        assertThat(limited.retryAfterSeconds()).isEqualTo(OptionalLong.empty());
        assertThat(limited.message()).isEqualTo("This tenant is limited to 2 calls at a time");

        first.close();
        first.close();
        admitted("support-bot", limits);
        limited("support-bot", limits);
        second.close();
    }

    @Test
    void aRefusedCallUsesNothing() {
        Limits limits = new Limits(2L, null, 1L);
        Permit inFlight = admitted("support-bot", limits);
        limited("support-bot", limits);
        limited("support-bot", limits);
        inFlight.close();

        admitted("support-bot", limits).close();
        limited("support-bot", limits);
    }

    @Test
    void keepsEachTenantSeparate() {
        Limits limits = new Limits(1L, null, null);
        admitted("support-bot", limits).close();
        limited("support-bot", limits);

        admitted("analytics", limits).close();
    }

    @Test
    void appliesChangedLimitsToTheSameCounts() {
        for (int i = 0; i < 9; i++) {
            admitted("support-bot", new Limits(10L, null, null)).close();
        }

        Limits smaller = new Limits(2L, null, null);
        assertThat(limited("support-bot", smaller).retryAfterSeconds()).hasValue(24);
        admitted("support-bot", new Limits(10L, null, null)).close();

        Permit before = admitted("support-bot", new Limits(null, null, 1L));
        admitted("support-bot", Limits.NONE).close();
        Permit unlimited = admitted("support-bot", Limits.NONE);
        unlimited.close();
        before.close();
        admitted("support-bot", new Limits(null, null, 1L));
    }

    @Test
    void anUnlimitedTenantIsNeverRefused() {
        for (int i = 0; i < 10_000; i++) {
            Permit permit = admitted("support-bot", Limits.NONE);
            permit.charge(1_000_000);
        }
    }

    @Test
    void holdsTheConcurrentLimitUnderContention() throws Exception {
        Limits limits = new Limits(null, null, 4L);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int call = 0; call < 200; call++) {
                    if (limiter.admit("support-bot", limits) instanceof Permit permit) {
                        most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        inFlight.decrementAndGet();
                        permit.close();
                    }
                }
            });
        }
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running =
                    tasks.stream().<Future<?>>map(threads::submit).toList();
            start.countDown();
            for (Future<?> task : running) {
                task.get();
            }
        }

        assertThat(most.get()).isBetween(1, 4);
        for (int i = 0; i < 4; i++) {
            admitted("support-bot", limits);
        }
        limited("support-bot", limits);
    }

    @Test
    void namesASingleCallInTheSingular() {
        Limits limits = new Limits(1L, null, 1L);
        Permit permit = admitted("support-bot", limits);

        assertThat(limited("support-bot", limits).message()).isEqualTo("This tenant is limited to 1 request a minute");
        wait(Duration.ofMinutes(1));
        assertThat(limited("support-bot", limits).message()).isEqualTo("This tenant is limited to 1 call at a time");
        permit.close();
    }

    @Test
    void rejectsLimitsThatAreNotPositive() {
        assertThatThrownBy(() -> new Limits(0L, null, null)).hasMessage("requestsPerMinute must be positive, was 0");
        assertThatThrownBy(() -> new Limits(null, -5L, null)).hasMessage("tokensPerMinute must be positive, was -5");
        assertThatThrownBy(() -> new Limits(null, null, 0L)).hasMessage("concurrent must be positive, was 0");
    }
}
