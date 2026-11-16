package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.gateway.audit.UsageReport;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Amount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.CallView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.OutcomeView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.SealView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.StepView;
import java.io.Serial;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

final class LiveWindow {

    private static final long BUCKET_SECONDS = 5;
    private static final int BUCKETS = 720;
    private static final int MINUTES = 15;
    private static final int KEYS = 2000;
    private static final int CALLS = 200;
    private static final int WAITING = 10_000;
    private static final int STEPS = 40;
    private static final String OTHER = "other";
    private static final long[] BOUNDS = new long[STEPS];

    static {
        for (int i = 0; i < STEPS; i++) {
            BOUNDS[i] = Math.round(16 * Math.pow(2, i / 2.0));
        }
    }

    private final Instant started;
    private Instant latest;
    private boolean skipped;
    private @Nullable Seal lastSeal;
    private final TreeMap<Long, BucketData> buckets = new TreeMap<>();
    private final TreeMap<Long, Map<FlowKey, FlowData>> flows = new TreeMap<>();
    private final TreeMap<Long, Map<GuardKey, GuardData>> guards = new TreeMap<>();
    private final Map<String, FlowKey> waiting = new Bounded<>(WAITING);
    private final Map<String, Call> calls = new Bounded<>(CALLS);

    LiveWindow(Instant started) {
        this.started = started;
        this.latest = started;
    }

    void add(AuditEntry entry) {
        switch (entry) {
            case Seal seal -> lastSeal = seal;
            case Intent intent -> add(intent);
            case Outcome outcome -> add(outcome);
        }
    }

    void skipped() {
        skipped = true;
    }

    Live snapshot(Instant now) {
        long nowSeconds = now.getEpochSecond();
        long lastStart = bucketStart(nowSeconds);
        long firstStart = Math.max(bucketStart(started.getEpochSecond()), lastStart - (BUCKETS - 1) * BUCKET_SECONDS);
        List<Bucket> bucketViews = new ArrayList<>();
        long[] recent = new long[STEPS];
        for (long start = firstStart; start <= lastStart; start += BUCKET_SECONDS) {
            BucketData data = buckets.getOrDefault(start, new BucketData());
            bucketViews.add(data.view(start));
            if (start + BUCKET_SECONDS > nowSeconds - 300) {
                for (int i = 0; i < STEPS; i++) {
                    recent[i] += data.latency[i];
                }
            }
        }
        long minute = nowSeconds / 60;
        Map<FlowKey, FlowData> flowTotals = new TreeMap<>(FlowKey.ORDER);
        flows.subMap(minute - (MINUTES - 1), true, minute, true)
                .values()
                .forEach(map ->
                        map.forEach((key, data) -> data.addTo(flowTotals.computeIfAbsent(key, k -> new FlowData()))));
        Map<GuardKey, GuardData> guardTotals = new TreeMap<>(GuardKey.ORDER);
        guards.subMap(minute - (MINUTES - 1), true, minute, true)
                .values()
                .forEach(map ->
                        map.forEach((key, data) -> data.addTo(guardTotals.computeIfAbsent(key, k -> new GuardData()))));
        List<CallView> callViews = new ArrayList<>();
        calls.values().forEach(call -> callViews.add(call.view()));
        Collections.reverse(callViews);
        boolean wasSkipped = skipped;
        skipped = false;
        Seal seal = lastSeal;
        return new Live(
                now,
                started,
                wasSkipped,
                List.copyOf(bucketViews),
                percentile(recent, 50),
                percentile(recent, 95),
                flowTotals.entrySet().stream()
                        .filter(entry -> entry.getValue().counted())
                        .map(entry -> entry.getValue().view(entry.getKey()))
                        .toList(),
                guardTotals.entrySet().stream()
                        .map(entry -> entry.getValue().view(entry.getKey()))
                        .toList(),
                List.copyOf(callViews),
                seal == null ? null : sealView(seal));
    }

    private void add(Intent intent) {
        if (!inWindow(intent.time())) {
            return;
        }
        Decision decision = intent.decision();
        FlowKey key = new FlowKey(intent.tenant(), intent.model(), intent.provider());
        if (decision instanceof Decision.Deny) {
            bucket(intent.time()).refused++;
        } else {
            waiting.put(intent.id(), key);
        }
        calls.put(intent.id(), new Call(intent));
        if (!inFlowWindow(intent.time())) {
            return;
        }
        FlowData flow = flow(intent.time(), key);
        if (decision instanceof Decision.Deny denial) {
            flow.refused.merge(denial.refusal().code(), 1L, Long::sum);
            flow.lastRefused = later(flow.lastRefused, intent.time());
        } else if (intent.id().contains(".")) {
            flow.fallbacks++;
            flow.lastFallback = later(flow.lastFallback, intent.time());
        }
        if (intent.redactions() != null) {
            flow.redacted += intent.redactions().values().values().stream()
                    .mapToLong(Integer::longValue)
                    .sum();
        }
        for (GuardStep step : intent.steps()) {
            GuardData guard = guard(intent.time(), new GuardKey(intent.tenant(), step.guard()));
            if (!(step.decision() instanceof Decision.Deny)) {
                guard.allowed++;
            } else if (step.monitored()) {
                guard.monitored++;
            } else {
                guard.refused++;
                guard.lastRefused = later(guard.lastRefused, intent.time());
            }
        }
    }

    private void add(Outcome outcome) {
        if (!inWindow(outcome.time())) {
            return;
        }
        FlowKey key = waiting.remove(outcome.id());
        if (key == null) {
            return;
        }
        Call call = calls.get(outcome.id());
        if (call != null) {
            call.outcome = outcome;
        }
        if (outcome.result() == Outcome.Result.DRY_RUN) {
            return;
        }
        boolean responded = outcome.result() == Outcome.Result.RESPONDED;
        Usage usage = outcome.usage();
        long input = responded && usage != null ? usage.inputTokens() : 0;
        long output = responded && usage != null ? usage.outputTokens() : 0;
        BucketData bucket = bucket(outcome.time());
        if (responded) {
            bucket.sent++;
            bucket.latency[step(outcome.duration().toMillis())]++;
        } else {
            bucket.failed++;
        }
        bucket.inputTokens += input;
        bucket.outputTokens += output;
        bucket.cost.merge(outcome.cost().currency(), outcome.cost().nanos(), Long::sum);
        if (!inFlowWindow(outcome.time())) {
            return;
        }
        FlowData flow = flow(outcome.time(), key);
        if (responded) {
            flow.sent++;
            flow.lastSent = later(flow.lastSent, outcome.time());
        } else {
            flow.failed++;
        }
        flow.inputTokens += input;
        flow.outputTokens += output;
        flow.cost.merge(outcome.cost().currency(), outcome.cost().nanos(), Long::sum);
    }

    private boolean inWindow(Instant time) {
        if (time.isAfter(latest)) {
            latest = time;
            buckets.headMap(oldestBucket()).clear();
            flows.headMap(oldestMinute()).clear();
            guards.headMap(oldestMinute()).clear();
        }
        return bucketStart(time.getEpochSecond()) >= oldestBucket();
    }

    private boolean inFlowWindow(Instant time) {
        return time.getEpochSecond() / 60 >= oldestMinute();
    }

    private long oldestBucket() {
        return bucketStart(latest.getEpochSecond()) - (BUCKETS - 1) * BUCKET_SECONDS;
    }

    private long oldestMinute() {
        return latest.getEpochSecond() / 60 - (MINUTES - 1);
    }

    private BucketData bucket(Instant time) {
        return buckets.computeIfAbsent(bucketStart(time.getEpochSecond()), start -> new BucketData());
    }

    private FlowData flow(Instant time, FlowKey key) {
        Map<FlowKey, FlowData> minute = flows.computeIfAbsent(time.getEpochSecond() / 60, m -> new HashMap<>());
        boolean full = !minute.containsKey(key) && minute.size() >= KEYS - 1;
        return minute.computeIfAbsent(full ? new FlowKey(OTHER, OTHER, OTHER) : key, k -> new FlowData());
    }

    private GuardData guard(Instant time, GuardKey key) {
        Map<GuardKey, GuardData> minute = guards.computeIfAbsent(time.getEpochSecond() / 60, m -> new HashMap<>());
        boolean full = !minute.containsKey(key) && minute.size() >= KEYS - 1;
        return minute.computeIfAbsent(full ? new GuardKey(OTHER, OTHER) : key, k -> new GuardData());
    }

    private static long bucketStart(long seconds) {
        return seconds - Math.floorMod(seconds, BUCKET_SECONDS);
    }

    private static int step(long millis) {
        for (int i = 0; i < STEPS - 1; i++) {
            if (millis <= BOUNDS[i]) {
                return i;
            }
        }
        return STEPS - 1;
    }

    private static @Nullable Long percentile(long[] latency, int percent) {
        long total = 0;
        for (long count : latency) {
            total += count;
        }
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

    private static @Nullable Instant later(@Nullable Instant current, @Nullable Instant time) {
        if (time == null) {
            return current;
        }
        return current == null || time.isAfter(current) ? time : current;
    }

    private static Amount amount(Money money) {
        return new Amount(
                money.toBigDecimal().stripTrailingZeros().toPlainString(),
                money.currency().getCurrencyCode());
    }

    private static Map<String, String> amounts(Map<Currency, Long> cost) {
        Map<String, String> amounts = new TreeMap<>();
        cost.forEach((currency, nanos) -> amounts.put(
                currency.getCurrencyCode(), amount(new Money(nanos, currency)).amount()));
        return amounts;
    }

    private static Map<Currency, Long> costs() {
        return new TreeMap<>(Comparator.comparing(Currency::getCurrencyCode));
    }

    private static SealView sealView(Seal seal) {
        return new SealView(
                seal.batch(), seal.records(), seal.time(), seal.root(), seal.previous(), seal.key(), seal.hmacKey());
    }

    private record FlowKey(String tenant, String model, String provider) {

        static final Comparator<FlowKey> ORDER = Comparator.comparing(FlowKey::tenant)
                .thenComparing(FlowKey::model)
                .thenComparing(FlowKey::provider);
    }

    private record GuardKey(String tenant, String guard) {

        static final Comparator<GuardKey> ORDER =
                Comparator.comparing(GuardKey::tenant).thenComparing(GuardKey::guard);
    }

    private static final class BucketData {

        long sent;
        long refused;
        long failed;
        long inputTokens;
        long outputTokens;
        final Map<Currency, Long> cost = costs();
        final long[] latency = new long[STEPS];

        Bucket view(long start) {
            return new Bucket(
                    start,
                    sent,
                    refused,
                    failed,
                    inputTokens,
                    outputTokens,
                    amounts(cost),
                    percentile(latency, 50),
                    percentile(latency, 95));
        }
    }

    private static final class FlowData {

        long sent;
        final Map<String, Long> refused = new TreeMap<>();
        long failed;
        long fallbacks;
        long inputTokens;
        long outputTokens;
        final Map<Currency, Long> cost = costs();
        long redacted;

        @Nullable
        Instant lastSent;

        @Nullable
        Instant lastRefused;

        @Nullable
        Instant lastFallback;

        boolean counted() {
            return sent + failed + fallbacks + redacted > 0 || !refused.isEmpty();
        }

        void addTo(FlowData total) {
            total.sent += sent;
            refused.forEach((code, count) -> total.refused.merge(code, count, Long::sum));
            total.failed += failed;
            total.fallbacks += fallbacks;
            total.inputTokens += inputTokens;
            total.outputTokens += outputTokens;
            cost.forEach((currency, nanos) -> total.cost.merge(currency, nanos, Long::sum));
            total.redacted += redacted;
            total.lastSent = later(total.lastSent, lastSent);
            total.lastRefused = later(total.lastRefused, lastRefused);
            total.lastFallback = later(total.lastFallback, lastFallback);
        }

        Flow view(FlowKey key) {
            return new Flow(
                    key.tenant(),
                    key.model(),
                    key.provider(),
                    sent,
                    new TreeMap<>(refused),
                    failed,
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

    private static final class GuardData {

        long allowed;
        long refused;
        long monitored;

        @Nullable
        Instant lastRefused;

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

    private static final class Call {

        private final Intent intent;

        @Nullable
        Outcome outcome;

        Call(Intent intent) {
            this.intent = intent;
        }

        CallView view() {
            Decision.Deny denial = intent.decision() instanceof Decision.Deny deny ? deny : null;
            Money reservation = intent.reservation();
            Outcome finished = outcome;
            return new CallView(
                    intent.id(),
                    intent.time(),
                    intent.tenant(),
                    intent.key(),
                    intent.endpoint().configName(),
                    intent.model(),
                    intent.provider(),
                    intent.upstream(),
                    intent.dataClass(),
                    intent.user() != null,
                    intent.id().contains("."),
                    intent.steps().stream().map(Call::step).toList(),
                    intent.allowed(),
                    denial == null ? null : denial.refusal().code(),
                    denial == null ? null : denial.message(),
                    reservation == null ? null : amount(reservation),
                    intent.redactions() == null ? Map.of() : intent.redactions().values(),
                    finished == null ? null : outcomeView(finished));
        }

        private static StepView step(GuardStep step) {
            Decision.Deny denial = step.decision() instanceof Decision.Deny deny ? deny : null;
            return new StepView(
                    step.guard(),
                    step.monitored(),
                    denial == null,
                    denial == null ? null : denial.refusal().code(),
                    denial == null ? null : denial.message(),
                    step.duration().toNanos() / 1000);
        }

        private static OutcomeView outcomeView(Outcome outcome) {
            Usage usage = outcome.usage();
            return new OutcomeView(
                    outcome.time(),
                    outcome.result().code(),
                    outcome.status(),
                    usage == null
                            ? null
                            : new UsageReport.Tokens(
                                    usage.inputTokens(),
                                    usage.outputTokens(),
                                    usage.cacheReadTokens(),
                                    usage.cacheWriteTokens()),
                    amount(outcome.cost()),
                    outcome.duration().toMillis());
        }
    }

    private static final class Bounded<K, V> extends LinkedHashMap<K, V> {

        @Serial
        private static final long serialVersionUID = 1L;

        private final int limit;

        Bounded(int limit) {
            this.limit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > limit;
        }
    }
}
