package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.gateway.audit.UsageReport;
import io.github.jamalakhras.tollgate.server.admin.LiveData.BucketData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.FlowKey;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardData;
import io.github.jamalakhras.tollgate.server.admin.LiveData.GuardKey;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
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

            @Schema(description = "Responded calls with an upstream status of 400 to 499, a subset of sent")
            long clientErrors,

            @Schema(description = "Responded calls with an upstream status of 500 or above, a subset of sent")
            long serverErrors,

            @Schema(description = "Calls that ended because the client went away, a subset of failed")
            long clientClosed,

            long inputTokens,
            long outputTokens,
            Map<String, String> cost,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis) {}

    record Totals(
            @Schema(
                    description =
                            "Seconds the sums cover: for totals, the span's start or the instance's start up to now; for previous, minutes times 60")
            long seconds,

            long sent,
            long refused,
            long failed,

            @Schema(description = "Responded calls with an upstream status of 400 to 499, a subset of sent")
            long clientErrors,

            @Schema(description = "Responded calls with an upstream status of 500 or above, a subset of sent")
            long serverErrors,

            @Schema(description = "Calls that ended because the client went away, a subset of failed")
            long clientClosed,

            long inputTokens,
            long outputTokens,
            Map<String, String> cost,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis) {}

    record Percentiles(long p50Millis, long p95Millis) {}

    record Latency(
            Map<String, Percentiles> providers, Map<String, Percentiles> tenants, Map<String, Percentiles> models) {}

    record Flow(
            String tenant,
            String model,
            String provider,
            long sent,
            Map<String, Long> refused,
            long failed,

            @Schema(description = "Responded calls with an upstream status of 400 to 499, a subset of sent")
            long clientErrors,

            @Schema(description = "Responded calls with an upstream status of 500 or above, a subset of sent")
            long serverErrors,

            @Schema(description = "Calls that ended because the client went away, a subset of failed")
            long clientClosed,

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
            NavigableMap<Long, BucketData> buckets,
            NavigableMap<Long, Map<FlowKey, FlowData>> flows,
            NavigableMap<Long, Map<GuardKey, GuardData>> guards,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis,
            List<CallView> calls,
            @Nullable SealView lastSeal) {}
}
