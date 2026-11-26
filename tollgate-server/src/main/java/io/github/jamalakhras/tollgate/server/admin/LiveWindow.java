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
import io.github.jamalakhras.tollgate.server.admin.LiveData.BucketData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.Cell;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowKey;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardKey;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Amount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.CallView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.OutcomeView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.StepView;
import java.io.Serial;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

final class LiveWindow {

    private static final int BUCKET_SECONDS = 5;
    private static final int BUCKETS = 1440;
    private static final int MINUTES = 60;
    private static final int RECENT_SECONDS = 300;
    private static final int KEYS = 500;
    private static final int CALLS = 200;
    private static final Duration SKIPPED_FOR = Duration.ofSeconds(60);
    private static final int WAITING = 10_000;
    private static final String OTHER = "other";

    private final Instant started;
    private Instant latest;
    private @Nullable Instant skippedAt;
    private @Nullable Seal lastSeal;
    private final TreeMap<Long, Cell<BucketData>> buckets = new TreeMap<>();
    private final TreeMap<Long, Cell<Map<FlowKey, FlowData>>> flows = new TreeMap<>();
    private final TreeMap<Long, Cell<Map<GuardKey, GuardData>>> guards = new TreeMap<>();
    private final Map<String, FlowKey> waiting = new Bounded<>(WAITING);
    private final Bounded<String, Call> calls = new Bounded<>(CALLS);

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

    void skipped(Instant at) {
        skippedAt = at;
    }

    Live snapshot(Instant now) {
        long nowSeconds = now.getEpochSecond();
        NavigableMap<Long, BucketData> bucketCounts = new TreeMap<>();
        buckets.forEach((start, cell) -> bucketCounts.put(start, cell.publish()));
        NavigableMap<Long, Map<FlowKey, FlowData>> flowCounts = new TreeMap<>();
        flows.forEach((minute, cell) -> flowCounts.put(minute, cell.publish()));
        NavigableMap<Long, Map<GuardKey, GuardData>> guardCounts = new TreeMap<>();
        guards.forEach((minute, cell) -> guardCounts.put(minute, cell.publish()));
        long[] recent = new long[LiveData.STEPS];
        bucketCounts
                .subMap(nowSeconds - RECENT_SECONDS - BUCKET_SECONDS, false, bucketStart(nowSeconds), true)
                .values()
                .forEach(data -> LiveData.add(recent, data.latency));
        List<CallView> callViews =
                calls.sequencedValues().reversed().stream().map(Call::view).toList();
        Instant skip = skippedAt;
        boolean wasSkipped = skip != null && !skip.isAfter(now) && now.isBefore(skip.plus(SKIPPED_FOR));
        Seal seal = lastSeal;
        return new Live(
                now,
                started,
                wasSkipped,
                Collections.unmodifiableNavigableMap(bucketCounts),
                Collections.unmodifiableNavigableMap(flowCounts),
                Collections.unmodifiableNavigableMap(guardCounts),
                LiveData.percentile(recent, 50),
                LiveData.percentile(recent, 95),
                callViews,
                seal == null ? null : LiveViews.sealView(seal));
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
            flow.lastRefused = LiveData.later(flow.lastRefused, intent.time());
        } else if (intent.id().contains(".")) {
            flow.fallbacks++;
            flow.lastFallback = LiveData.later(flow.lastFallback, intent.time());
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
                guard.lastRefused = LiveData.later(guard.lastRefused, intent.time());
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
        Integer status = outcome.status();
        boolean clientError = responded && status != null && status >= 400 && status < 500;
        boolean serverError = responded && status != null && status >= 500;
        if (responded) {
            bucket.sent++;
            bucket.latency[LiveData.step(outcome.duration().toMillis())]++;
            if (clientError) {
                bucket.clientErrors++;
            } else if (serverError) {
                bucket.serverErrors++;
            }
        } else {
            bucket.failed++;
            if (outcome.result() == Outcome.Result.CLIENT_CLOSED) {
                bucket.clientClosed++;
            }
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
            flow.record()[LiveData.step(outcome.duration().toMillis())]++;
            if (clientError) {
                flow.clientErrors++;
            } else if (serverError) {
                flow.serverErrors++;
            }
            flow.lastSent = LiveData.later(flow.lastSent, outcome.time());
        } else {
            flow.failed++;
            if (outcome.result() == Outcome.Result.CLIENT_CLOSED) {
                flow.clientClosed++;
            }
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
        return buckets.computeIfAbsent(
                        bucketStart(time.getEpochSecond()), start -> new Cell<>(new BucketData(), BucketData::copy))
                .write();
    }

    private FlowData flow(Instant time, FlowKey key) {
        Map<FlowKey, FlowData> minute = flows.computeIfAbsent(
                        time.getEpochSecond() / 60, m -> new Cell<>(new HashMap<>(), LiveWindow::copyFlows))
                .write();
        boolean full = !minute.containsKey(key) && minute.size() >= KEYS - 1;
        return minute.computeIfAbsent(full ? new FlowKey(OTHER, OTHER, OTHER) : key, k -> new FlowData());
    }

    private GuardData guard(Instant time, GuardKey key) {
        Map<GuardKey, GuardData> minute = guards.computeIfAbsent(
                        time.getEpochSecond() / 60, m -> new Cell<>(new HashMap<>(), LiveWindow::copyGuards))
                .write();
        boolean full = !minute.containsKey(key) && minute.size() >= KEYS - 1;
        return minute.computeIfAbsent(full ? new GuardKey(OTHER, OTHER) : key, k -> new GuardData());
    }

    private static Map<FlowKey, FlowData> copyFlows(Map<FlowKey, FlowData> map) {
        return LiveData.copy(map, FlowData::copy);
    }

    private static Map<GuardKey, GuardData> copyGuards(Map<GuardKey, GuardData> map) {
        return LiveData.copy(map, GuardData::copy);
    }

    private static long bucketStart(long seconds) {
        return seconds - Math.floorMod(seconds, BUCKET_SECONDS);
    }

    private static Amount amount(Money money) {
        return new Amount(LiveViews.plain(money), money.currency().getCurrencyCode());
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
