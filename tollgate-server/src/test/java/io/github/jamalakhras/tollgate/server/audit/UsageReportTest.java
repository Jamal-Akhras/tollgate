package io.github.jamalakhras.tollgate.server.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.server.audit.UsageReport.Grouping;
import io.github.jamalakhras.tollgate.server.audit.UsageReport.Row;
import io.github.jamalakhras.tollgate.server.audit.UsageReport.Tokens;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UsageReportTest {

    private static final Instant NOVEMBER = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant DECEMBER = Instant.parse("2026-12-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(Samples.TIME, ZoneOffset.UTC);

    @TempDir
    private Path directory;

    @BeforeEach
    void writeTrail() throws Exception {
        try (AuditLog log = AuditLog.start(JsonLinesAuditSink.open(directory), Samples.KEYS, CLOCK, true)) {
            log.append(Samples.intent("answered"));
            log.append(Samples.outcome("answered"));
            log.append(Samples.refused("refused"));
            log.append(Samples.intent("cut-off"));
            log.append(Samples.intent("estimated"));
            log.append(new Outcome(
                    "estimated",
                    Samples.TIME,
                    Outcome.Result.DRY_RUN,
                    null,
                    null,
                    Samples.outcome("x").cost(),
                    Duration.ZERO,
                    null));
        }
    }

    @Test
    void sumsCallsRefusalsTokensAndCostForEachTenant() throws Exception {
        UsageReport report = UsageReport.of(directory, NOVEMBER, DECEMBER, Grouping.TENANT);

        assertThat(report.by()).isEqualTo("tenant");
        assertThat(report.rows())
                .containsExactly(new Row(
                        "support-bot",
                        1,
                        1,
                        Map.of("policy_denied", 1L),
                        new Tokens(812, 304, 0, 0),
                        Map.of("USD", "0.006996")));
    }

    @Test
    void groupsByModelKeyOrDay() throws Exception {
        assertThat(UsageReport.of(directory, NOVEMBER, DECEMBER, Grouping.MODEL).rows())
                .extracting(Row::key, Row::calls)
                .containsExactly(tuple("claude-sonnet", 1L), tuple("gpt", 0L));
        assertThat(UsageReport.of(directory, NOVEMBER, DECEMBER, Grouping.KEY).rows())
                .extracting(Row::key)
                .containsExactly("k7Qm2xPa4bZr");
        assertThat(UsageReport.of(directory, NOVEMBER, DECEMBER, Grouping.DAY).rows())
                .extracting(Row::key)
                .containsExactly("2026-11-02");
    }

    @Test
    void leavesOutRequestsOutsideThePeriod() throws Exception {
        assertThat(UsageReport.of(directory, DECEMBER, DECEMBER.plusSeconds(86_400), Grouping.TENANT)
                        .rows())
                .isEmpty();
        assertThat(UsageReport.of(directory, NOVEMBER, Samples.TIME, Grouping.TENANT)
                        .rows())
                .isEmpty();
    }

    @Test
    void readsDatesAndDefaultsToThisMonth() throws Exception {
        UsageReport report = UsageReport.of(directory, null, null, "day", CLOCK);

        assertThat(report.from()).isEqualTo(NOVEMBER);
        assertThat(report.to()).isEqualTo(Instant.parse("2026-11-03T00:00:00Z"));
        assertThat(UsageReport.of(directory, "2026-11-02", "2026-11-03", "tenant", CLOCK)
                        .rows())
                .hasSize(1);
    }

    @Test
    void explainsABadQuery() {
        assertThatThrownBy(() -> UsageReport.of(directory, "November", null, "tenant", CLOCK))
                .hasMessage("from must be a date such as 2026-11-01, was November");
        assertThatThrownBy(() -> UsageReport.of(directory, "2026-11-02", "2026-11-02", "tenant", CLOCK))
                .hasMessage("from must be before to, which is the first day not included");
        assertThatThrownBy(() -> UsageReport.of(directory, null, null, "week", CLOCK))
                .hasMessage("by must be one of tenant, model, key, day, was week");
    }

    @Test
    void skipsALineStillBeingWritten() throws Exception {
        Path file = AuditDirectory.files(directory).getLast();
        Files.writeString(file, "{\"v\":1,\"type\":\"intent\",\"id\":\"half", StandardOpenOption.APPEND);

        assertThat(UsageReport.of(directory, NOVEMBER, DECEMBER, Grouping.TENANT)
                        .rows())
                .singleElement()
                .extracting(Row::calls)
                .isEqualTo(1L);
    }
}
