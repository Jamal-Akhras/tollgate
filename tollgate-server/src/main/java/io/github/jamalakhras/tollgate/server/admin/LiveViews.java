package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.gateway.audit.UsageReport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class LiveViews {

    private LiveViews() {}

    static SealView sealView(Seal seal) {
        return new SealView(
                seal.batch(), seal.records(), seal.time(), seal.root(), seal.previous(), seal.key(), seal.hmacKey());
    }

    static String plain(Money money) {
        return money.toBigDecimal().stripTrailingZeros().toPlainString();
    }

    record Bucket(
            long start,
            long sent,
            long refused,
            long failed,
            long inputTokens,
            long outputTokens,
            Map<String, String> cost,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis) {}

    record Flow(
            String tenant,
            String model,
            String provider,
            long sent,
            Map<String, Long> refused,
            long failed,
            long fallbacks,
            long inputTokens,
            long outputTokens,
            Map<String, String> cost,
            long redacted,
            @Nullable Instant lastSent,
            @Nullable Instant lastRefused,
            @Nullable Instant lastFallback) {}

    record GuardCount(
            String tenant,
            String guard,
            long allowed,
            long refused,
            long monitored,
            @Nullable Instant lastRefused) {}

    record SealView(long batch, int records, Instant time, String root, String previous, String key, String hmacKey) {}

    record Amount(String amount, String currency) {}

    record StepView(
            String guard,
            boolean monitored,
            boolean allowed,
            @Nullable String code,
            @Nullable String message,
            long micros) {}

    record OutcomeView(
            Instant time,
            String result,
            @Nullable Integer status,
            UsageReport.@Nullable Tokens tokens,
            Amount cost,
            long millis) {}

    record CallView(
            String id,
            Instant time,
            String tenant,
            String key,
            String endpoint,
            String model,
            String provider,
            String upstream,
            @Nullable String dataClass,
            boolean attributed,
            boolean fallback,
            List<StepView> steps,
            boolean allowed,
            @Nullable String code,
            @Nullable String message,
            @Nullable Amount reservation,
            Map<String, Integer> redacted,
            @Nullable OutcomeView outcome) {}

    record Live(
            Instant now,
            Instant since,
            boolean skipped,
            List<Bucket> buckets,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis,
            List<Flow> flows,
            List<GuardCount> guards,
            List<CallView> calls,
            @Nullable SealView lastSeal) {}
}
