package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.CallView;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.SealView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(name = "tollgate.console.enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Live", description = "What the gateway is doing now, read from the audit trail")
@RequestMapping(path = "/admin/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class LiveController {

    private static final int BUCKETS_PER_MINUTE = 60 / LiveWindow.BUCKET_SECONDS;

    private final Gateway gateway;
    private final LiveTail tail;

    LiveController(Gateway gateway, LiveTail tail) {
        this.gateway = gateway;
        this.tail = tail;
    }

    @GetMapping("/live")
    @Operation(
            summary = "Show what is happening now",
            description = "Totals for each 5 seconds, the calls of each tenant, model and provider and the verdicts of"
                    + " each guard over the last 15 minutes, the calls in flight to each provider, the calls refused"
                    + " by a rate limit for each tenant since the start, and the last seal. Counted from the audit"
                    + " trail since this instance started.")
    @ApiResponse(responseCode = "200", description = "The live snapshot")
    @ApiResponse(
            responseCode = "400",
            description = "minutes is not between 1 and 60",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off, so there is nothing to follow",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    LiveSnapshot live(
            @Parameter(description = "How many minutes of 5-second totals to return, from 1 to 60")
                    @RequestParam(defaultValue = "15")
                    int minutes) {
        requireAudit();
        if (minutes < 1 || minutes > 60) {
            throw AdminException.badQuery("minutes must be between 1 and 60, was " + minutes);
        }
        Live live = tail.current();
        List<Bucket> all = live.buckets();
        List<Bucket> buckets = all.subList(Math.max(0, all.size() - minutes * BUCKETS_PER_MINUTE), all.size());
        Instant since = buckets.isEmpty()
                ? live.since()
                : Instant.ofEpochSecond(buckets.getFirst().start());
        return new LiveSnapshot(
                live.now(),
                since,
                live.skipped(),
                LiveWindow.BUCKET_SECONDS,
                List.copyOf(buckets),
                live.p50Millis(),
                live.p95Millis(),
                live.flows(),
                live.guards(),
                inFlight(),
                limited(),
                live.lastSeal());
    }

    @GetMapping("/activity")
    @Operation(
            summary = "List the latest calls",
            description = "The most recent calls the gateway decided, newest first, each with its guard steps and its"
                    + " outcome once there is one. Taken from the audit trail since this instance started, without"
                    + " prompts, answers or user IDs.")
    @ApiResponse(responseCode = "200", description = "The calls")
    @ApiResponse(
            responseCode = "400",
            description = "limit is not between 1 and 200",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off, so there is nothing to follow",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    List<CallView> activity(
            @Parameter(description = "How many calls to return, from 1 to 200") @RequestParam(defaultValue = "50")
                    int limit) {
        requireAudit();
        if (limit < 1 || limit > 200) {
            throw AdminException.badQuery("limit must be between 1 and 200, was " + limit);
        }
        return tail.current().calls().stream().limit(limit).toList();
    }

    private void requireAudit() {
        if (gateway.auditDirectory().isEmpty()) {
            throw AdminException.auditOff("The live view reads the audit trail, and audit.mode is off");
        }
    }

    private static Map<String, Long> inFlight() {
        Map<String, Long> byProvider = new TreeMap<>();
        for (Gauge gauge :
                Metrics.globalRegistry.find("tollgate.providers.in_flight").gauges()) {
            String provider = gauge.getId().getTag("provider");
            if (provider != null) {
                byProvider.merge(provider, Math.round(gauge.value()), Long::sum);
            }
        }
        return byProvider;
    }

    private static Map<String, Long> limited() {
        Map<String, Long> byTenant = new TreeMap<>();
        for (Counter counter :
                Metrics.globalRegistry.find("tollgate.limits.refused").counters()) {
            String tenant = counter.getId().getTag("tenant");
            if (tenant != null) {
                byTenant.merge(tenant, Math.round(counter.count()), Long::sum);
            }
        }
        return byTenant;
    }

    record LiveSnapshot(
            Instant now,
            Instant since,
            boolean skipped,
            int bucketSeconds,
            List<Bucket> buckets,
            @Nullable Long p50Millis,
            @Nullable Long p95Millis,
            List<Flow> flows,
            List<GuardCount> guards,
            Map<String, Long> inFlight,
            Map<String, Long> limited,
            @Nullable SealView lastSeal) {}
}
