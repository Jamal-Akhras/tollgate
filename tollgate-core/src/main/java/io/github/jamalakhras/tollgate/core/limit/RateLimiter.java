package io.github.jamalakhras.tollgate.core.limit;

import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

public final class RateLimiter {

    private static final long MINUTE = 60_000_000_000L;
    private static final long SECOND = 1_000_000_000L;

    private final LongSupplier nanoTime;
    private final Map<String, Counts> counts = new ConcurrentHashMap<>();

    public RateLimiter(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public Result admit(String tenant, Limits limits) {
        if (limits.equals(Limits.NONE)) {
            return new Permit(null, limits);
        }
        Counts state = counts.computeIfAbsent(tenant, name -> new Counts());
        Limited limited = state.admit(limits, nanoTime.getAsLong());
        return limited == null ? new Permit(state, limits) : limited;
    }

    public sealed interface Result permits Permit, Limited {}

    public enum Limit {
        REQUESTS_PER_MINUTE("requestsPerMinute", "request", "requests", "a minute"),
        TOKENS_PER_MINUTE("tokensPerMinute", "token", "tokens", "a minute"),
        CONCURRENT("concurrent", "call", "calls", "at a time");

        private final String configName;
        private final String one;
        private final String many;
        private final String per;

        Limit(String configName, String one, String many, String per) {
            this.configName = configName;
            this.one = one;
            this.many = many;
            this.per = per;
        }

        public String configName() {
            return configName;
        }
    }

    public record Limited(Limit limit, long value, OptionalLong retryAfterSeconds) implements Result {

        public String message() {
            return "This tenant is limited to " + value + " " + (value == 1 ? limit.one : limit.many) + " " + limit.per;
        }
    }

    public final class Permit implements Result, AutoCloseable {

        private final @Nullable Counts state;
        private final Limits limits;
        private boolean closed;

        private Permit(@Nullable Counts state, Limits limits) {
            this.state = state;
            this.limits = limits;
        }

        public void charge(long tokens) {
            Long perMinute = limits.tokensPerMinute();
            if (state != null && perMinute != null) {
                state.charge(tokens, perMinute, nanoTime.getAsLong());
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (state != null && limits.concurrent() != null) {
                state.release();
            }
        }
    }

    private static final class Counts {

        private final Bucket requests = new Bucket();
        private final Bucket tokens = new Bucket();
        private long calls;

        synchronized @Nullable Limited admit(Limits limits, long now) {
            Long requestsPerMinute = limits.requestsPerMinute();
            if (requestsPerMinute != null) {
                long wait = requests.backlog(now) - (MINUTE - Bucket.cost(1, requestsPerMinute));
                if (wait > 0) {
                    return limited(Limit.REQUESTS_PER_MINUTE, requestsPerMinute, wait);
                }
            }
            Long tokensPerMinute = limits.tokensPerMinute();
            if (tokensPerMinute != null) {
                long wait = tokens.backlog(now) - MINUTE;
                if (wait > 0) {
                    return limited(Limit.TOKENS_PER_MINUTE, tokensPerMinute, wait);
                }
            }
            Long concurrent = limits.concurrent();
            if (concurrent != null && calls >= concurrent) {
                return new Limited(Limit.CONCURRENT, concurrent, OptionalLong.empty());
            }
            if (requestsPerMinute != null) {
                requests.take(1, requestsPerMinute, now);
            }
            if (concurrent != null) {
                calls++;
            }
            return null;
        }

        synchronized void charge(long used, long perMinute, long now) {
            tokens.take(used, perMinute, now);
        }

        synchronized void release() {
            calls--;
        }

        private static Limited limited(Limit limit, long perMinute, long waitNanos) {
            long seconds = Math.ceilDiv(waitNanos, SECOND);
            return new Limited(limit, perMinute, OptionalLong.of(Math.max(1, seconds)));
        }
    }

    private static final class Bucket {

        private long fullAt = Long.MIN_VALUE;

        long backlog(long now) {
            return Math.max(fullAt, now) - now;
        }

        void take(long amount, long perMinute, long now) {
            fullAt = Math.max(fullAt, now) + cost(amount, perMinute);
        }

        static long cost(long amount, long perMinute) {
            return Math.round((double) amount * MINUTE / perMinute);
        }
    }
}
