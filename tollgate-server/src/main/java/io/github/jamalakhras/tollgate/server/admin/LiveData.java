package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Totals;
import java.time.Instant;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.jspecify.annotations.Nullable;

final class LiveData {

    static final int STEPS = 40;
    private static final long[] BOUNDS = IntStream.range(0, STEPS)
            .mapToLong(i -> Math.round(16 * Math.pow(2, i / 2.0)))
            .toArray();
    private static final long[] NONE = new long[0];

    private LiveData() {}

    static int step(long millis) {
        for (int i = 0; i < STEPS - 1; i++) {
            if (millis <= BOUNDS[i]) {
                return i;
            }
        }
        return STEPS - 1;
    }

    static @Nullable Long percentile(long[] latency, int percent) {
        long total = LongStream.of(latency).sum();
        if (total == 0) {
            return null;
        }
        long target = (total * percent + 99) / 100;
        long running = 0;
        for (int i = 0; i < STEPS; i++) {
            running += latency[i];
            if (running >= target) {
                return BOUNDS[i];
            }
        }
        return BOUNDS[STEPS - 1];
    }

    static void add(long[] total, long[] latency) {
        for (int i = 0; i < STEPS; i++) {
            total[i] += latency[i];
        }
    }

    static @Nullable Instant later(@Nullable Instant current, @Nullable Instant time) {
        if (time == null) {
            return current;
        }
        return current == null || time.isAfter(current) ? time : current;
    }

    static Map<String, String> amounts(Map<Currency, Long> cost) {
        Map<String, String> amounts = new TreeMap<>();
        cost.forEach((currency, nanos) ->
                amounts.put(currency.getCurrencyCode(), LiveViews.plain(new Money(nanos, currency))));
        return amounts;
    }

    static Map<Currency, Long> costs() {
        return new TreeMap<>(Comparator.comparing(Currency::getCurrencyCode));
    }

    static <K, V> Map<K, V> copy(Map<K, V> map, UnaryOperator<V> copy) {
        Map<K, V> copied = new HashMap<>();
        map.forEach((key, value) -> copied.put(key, copy.apply(value)));
        return copied;
    }

    static final class Cell<T> {

        private final UnaryOperator<T> copy;
        private T value;
        private boolean published;

        Cell(T value, UnaryOperator<T> copy) {
            this.value = value;
            this.copy = copy;
        }

        T write() {
            if (published) {
                value = copy.apply(value);
                published = false;
            }
            return value;
        }

        T publish() {
            published = true;
            return value;
        }
    }

    record FlowKey(String tenant, String model, String provider) {

        static final Comparator<FlowKey> ORDER = Comparator.comparing(FlowKey::tenant)
                .thenComparing(FlowKey::model)
                .thenComparing(FlowKey::provider);
    }

    record GuardKey(String tenant, String guard) {

        static final Comparator<GuardKey> ORDER =
                Comparator.comparing(GuardKey::tenant).thenComparing(GuardKey::guard);
    }

    static final class BucketData {

        long sent;
        long refused;
        long failed;
        long clientErrors;
        long serverErrors;
        long clientClosed;
        long inputTokens;
        long outputTokens;
        final Map<Currency, Long> cost = costs();
        final long[] latency = new long[STEPS];

        BucketData copy() {
            BucketData copy = new BucketData();
            addTo(copy);
            return copy;
        }

        void addTo(BucketData total) {
            total.sent += sent;
            total.refused += refused;
            total.failed += failed;
            total.clientErrors += clientErrors;
            total.serverErrors += serverErrors;
            total.clientClosed += clientClosed;
            total.inputTokens += inputTokens;
            total.outputTokens += outputTokens;
            cost.forEach((currency, nanos) -> total.cost.merge(currency, nanos, Long::sum));
            LiveData.add(total.latency, latency);
        }

        Bucket view(long start) {
            return new Bucket(
                    start,
                    sent,
                    refused,
                    failed,
                    clientErrors,
                    serverErrors,
                    clientClosed,
                    inputTokens,
                    outputTokens,
                    amounts(cost),
                    percentile(latency, 50),
                    percentile(latency, 95));
        }

        Totals totals(long seconds) {
            return new Totals(
                    seconds,
                    sent,
                    refused,
                    failed,
                    clientErrors,
                    serverErrors,
                    clientClosed,
                    inputTokens,
                    outputTokens,
                    amounts(cost),
                    percentile(latency, 50),
                    percentile(latency, 95));
        }
    }

    static final class FlowData {

        long sent;
        final Map<String, Long> refused = new TreeMap<>();
        long failed;
        long clientErrors;
        long serverErrors;
        long clientClosed;
        long fallbacks;
        long inputTokens;
        long outputTokens;
        final Map<Currency, Long> cost = costs();
        long redacted;
        private long[] latency = NONE;

        @Nullable
        Instant lastSent;

        @Nullable
        Instant lastRefused;

        @Nullable
        Instant lastFallback;

        long[] latency() {
            return latency;
        }

        long[] record() {
            if (latency.length == 0) {
                latency = new long[STEPS];
            }
            return latency;
        }

        boolean responded() {
            return latency.length > 0;
        }

        boolean counted() {
            return sent + failed + fallbacks + redacted > 0 || !refused.isEmpty();
        }

        FlowData copy() {
            FlowData copy = new FlowData();
            addTo(copy);
            return copy;
        }

        void addTo(FlowData total) {
            total.sent += sent;
            refused.forEach((code, count) -> total.refused.merge(code, count, Long::sum));
            total.failed += failed;
            total.clientErrors += clientErrors;
            total.serverErrors += serverErrors;
            total.clientClosed += clientClosed;
            total.fallbacks += fallbacks;
            total.inputTokens += inputTokens;
            total.outputTokens += outputTokens;
            cost.forEach((currency, nanos) -> total.cost.merge(currency, nanos, Long::sum));
            total.redacted += redacted;
            total.lastSent = later(total.lastSent, lastSent);
            total.lastRefused = later(total.lastRefused, lastRefused);
            total.lastFallback = later(total.lastFallback, lastFallback);
            if (responded()) {
                LiveData.add(total.record(), latency);
            }
        }

        Flow view(FlowKey key) {
            return new Flow(
                    key.tenant(),
                    key.model(),
                    key.provider(),
                    sent,
                    new TreeMap<>(refused),
                    failed,
                    clientErrors,
                    serverErrors,
                    clientClosed,
                    fallbacks,
                    inputTokens,
                    outputTokens,
                    amounts(cost),
                    redacted,
                    lastSent,
                    lastRefused,
                    lastFallback);
        }
    }

    static final class GuardData {

        long allowed;
        long refused;
        long monitored;

        @Nullable
        Instant lastRefused;

        GuardData copy() {
            GuardData copy = new GuardData();
            addTo(copy);
            return copy;
        }

        void addTo(GuardData total) {
            total.allowed += allowed;
            total.refused += refused;
            total.monitored += monitored;
            total.lastRefused = later(total.lastRefused, lastRefused);
        }

        GuardCount view(GuardKey key) {
            return new GuardCount(key.tenant(), key.guard(), allowed, refused, monitored, lastRefused);
        }
    }
}
