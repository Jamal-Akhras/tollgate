package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.server.admin.LiveData.BucketData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowKey;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardKey;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Latency;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Percentiles;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Totals;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class LiveAnswer {

    private LiveAnswer() {}

    static Answer of(Live live, int minutes, int step) {
        long nowSeconds = live.now().getEpochSecond();
        int count = minutes * 60 / step;
        long last = start(nowSeconds, step);
        long window = last - (count - 1L) * step;
        long first = Math.max(window, start(live.since().getEpochSecond(), step));
        List<Bucket> buckets = new ArrayList<>();
        for (long start = first; start <= last; start += step) {
            buckets.add(sum(live.buckets(), start, start + step).view(start));
        }
        long minute = nowSeconds / 60;
        long spanStart = (minute - (minutes - 1)) * 60;
        long running = live.since().getEpochSecond();
        Totals totals = sum(live.buckets(), spanStart, nowSeconds + 1)
                .totals(Math.max(0, nowSeconds - Math.max(spanStart, running)));
        long earlier = spanStart - minutes * 60L;
        Totals previous = live.since().isAfter(Instant.ofEpochSecond(earlier))
                ? null
                : sum(live.buckets(), earlier, spanStart).totals(minutes * 60L);
        Map<FlowKey, FlowData> flows = new TreeMap<>(FlowKey.ORDER);
        live.flows()
                .subMap(minute - (minutes - 1), true, minute, true)
                .values()
                .forEach(
                        map -> map.forEach((key, data) -> data.addTo(flows.computeIfAbsent(key, k -> new FlowData()))));
        Map<GuardKey, GuardData> guards = new TreeMap<>(GuardKey.ORDER);
        live.guards()
                .subMap(minute - (minutes - 1), true, minute, true)
                .values()
                .forEach(map ->
                        map.forEach((key, data) -> data.addTo(guards.computeIfAbsent(key, k -> new GuardData()))));
        return new Answer(
                buckets.isEmpty() ? live.since() : Instant.ofEpochSecond(first),
                List.copyOf(buckets),
                totals,
                previous,
                latency(flows),
                flows.entrySet().stream()
                        .filter(entry -> entry.getValue().counted())
                        .map(entry -> entry.getValue().view(entry.getKey()))
                        .toList(),
                guards.entrySet().stream()
                        .map(entry -> entry.getValue().view(entry.getKey()))
                        .toList());
    }

    private static long start(long seconds, int step) {
        return seconds - Math.floorMod(seconds, step);
    }

    private static BucketData sum(NavigableMap<Long, BucketData> buckets, long from, long to) {
        BucketData sum = new BucketData();
        buckets.subMap(from, true, to, false).values().forEach(data -> data.addTo(sum));
        return sum;
    }

    private static Latency latency(Map<FlowKey, FlowData> flows) {
        return new Latency(
                percentiles(flows, FlowKey::provider),
                percentiles(flows, FlowKey::tenant),
                percentiles(flows, FlowKey::model));
    }

    private static Map<String, Percentiles> percentiles(Map<FlowKey, FlowData> flows, Function<FlowKey, String> name) {
        Map<String, long[]> histograms = new TreeMap<>();
        flows.forEach((key, data) -> {
            if (data.responded()) {
                LiveData.add(
                        histograms.computeIfAbsent(name.apply(key), n -> new long[LiveData.STEPS]), data.latency());
            }
        });
        Map<String, Percentiles> percentiles = new TreeMap<>();
        histograms.forEach((n, histogram) -> {
            Long p50 = LiveData.percentile(histogram, 50);
            Long p95 = LiveData.percentile(histogram, 95);
            if (p50 != null && p95 != null) {
                percentiles.put(n, new Percentiles(p50, p95));
            }
        });
        return percentiles;
    }

    record Answer(
            Instant since,
            List<Bucket> buckets,
            Totals totals,
            @Nullable Totals previous,
            Latency latency,
            List<Flow> flows,
            List<GuardCount> guards) {}
}
