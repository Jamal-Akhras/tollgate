package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Redactions;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

class LiveWindowTest {

    private static final Instant T = Instant.parse("2026-11-16T10:00:00Z");
    private static final Currency USD = Currency.getInstance("USD");

    private final LiveWindow window = new LiveWindow(T.minus(Duration.ofHours(3)));

    private static Intent intent(String id, Instant time, String tenant, List<GuardStep> steps, Decision decision) {
        return LiveRecords.intent(id, time, tenant, steps, decision, null);
    }

    private static Intent allowed(String id, Instant time) {
        return intent(id, time, "app", List.of(), Decision.ALLOW);
    }

    private static Outcome outcome(String id, Instant time, Outcome.Result result, long millis) {
        return LiveRecords.outcome(id, time, result, millis, new Money(500_000_000, USD));
    }

    private static GuardStep step(String guard, Decision decision, boolean monitored) {
        return new GuardStep(guard, decision, Duration.ofNanos(2500), monitored);
    }

    private static Decision deny(Refusal refusal) {
        return Decision.deny(refusal, "no");
    }

    private static Flow onlyFlow(Live live) {
        assertThat(live.flows()).hasSize(1);
        return live.flows().getFirst();
    }

    private static long sent(Live live) {
        return live.buckets().stream().mapToLong(Bucket::sent).sum();
    }

    @Test
    void aSentCallAddsToItsFlowAndBucket() {
        window.add(allowed("c1", T));
        window.add(outcome("c1", T.plusSeconds(1), Outcome.Result.RESPONDED, 100));

        Live live = window.snapshot(T.plusSeconds(2));

        Flow flow = onlyFlow(live);
        assertThat(flow.tenant()).isEqualTo("app");
        assertThat(flow.model()).isEqualTo("qwen3");
        assertThat(flow.provider()).isEqualTo("ollama");
        assertThat(flow.sent()).isEqualTo(1);
        assertThat(flow.inputTokens()).isEqualTo(10);
        assertThat(flow.outputTokens()).isEqualTo(5);
        assertThat(flow.cost()).containsExactly(Map.entry("USD", "0.5"));
        assertThat(flow.lastSent()).isEqualTo(T.plusSeconds(1));
        Bucket bucket = live.buckets().getLast();
        assertThat(bucket.start()).isEqualTo(T.getEpochSecond());
        assertThat(bucket.sent()).isEqualTo(1);
        assertThat(bucket.inputTokens()).isEqualTo(10);
        assertThat(bucket.cost()).containsEntry("USD", "0.5");
        assertThat(bucket.p50Millis()).isEqualTo(128L);
    }

    @Test
    void aRefusalByAGuardCountsInTheFlowAndTheGuard() {
        Decision denial = deny(Refusal.POLICY_DENIED);
        window.add(intent("c1", T, "app", List.of(step("tool_names", denial, false)), denial));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).refused()).containsExactly(Map.entry("policy_denied", 1L));
        assertThat(onlyFlow(live).lastRefused()).isEqualTo(T);
        GuardCount guard = live.guards().getFirst();
        assertThat(guard.guard()).isEqualTo("tool_names");
        assertThat(guard.refused()).isEqualTo(1);
        assertThat(guard.allowed()).isZero();
        assertThat(guard.lastRefused()).isEqualTo(T);
        assertThat(live.buckets().getLast().refused()).isEqualTo(1);
    }

    @Test
    void aMonitoredStepIsCountedAsMonitoredAndLetsTheCallThrough() {
        window.add(
                intent("c1", T, "app", List.of(step("tool_names", deny(Refusal.POLICY_DENIED), true)), Decision.ALLOW));

        Live live = window.snapshot(T);

        GuardCount guard = live.guards().getFirst();
        assertThat(guard.monitored()).isEqualTo(1);
        assertThat(guard.refused()).isZero();
        assertThat(guard.lastRefused()).isNull();
        assertThat(live.buckets().getLast().refused()).isZero();
    }

    @Test
    void aBudgetRefusalRefusesNoStep() {
        window.add(intent(
                "c1", T, "app", List.of(step("data_class", Decision.ALLOW, false)), deny(Refusal.BUDGET_EXCEEDED)));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).refused()).containsExactly(Map.entry("budget_exceeded", 1L));
        GuardCount guard = live.guards().getFirst();
        assertThat(guard.allowed()).isEqualTo(1);
        assertThat(guard.refused()).isZero();
        assertThat(guard.lastRefused()).isNull();
    }

    @Test
    void anAllowedIntentWithADotInItsIdIsAFallbackAttempt() {
        window.add(allowed("c1.2", T));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).fallbacks()).isEqualTo(1);
        assertThat(onlyFlow(live).lastFallback()).isEqualTo(T);
        assertThat(live.calls().getFirst().fallback()).isTrue();
    }

    @Test
    void aDryRunAddsNothing() {
        window.add(allowed("c1", T));
        window.add(outcome("c1", T, Outcome.Result.DRY_RUN, 100));

        Live live = window.snapshot(T);

        assertThat(live.flows()).isEmpty();
        assertThat(live.buckets().getLast().sent()).isZero();
        assertThat(live.buckets().getLast().failed()).isZero();
        assertThat(live.calls().getFirst().outcome()).isNotNull();
    }

    @Test
    void aFailedOutcomeAddsAFailureAndItsCostButNoTokens() {
        window.add(allowed("c1", T));
        window.add(outcome("c1", T, Outcome.Result.TIMED_OUT, 100));

        Live live = window.snapshot(T);

        Flow flow = onlyFlow(live);
        assertThat(flow.failed()).isEqualTo(1);
        assertThat(flow.sent()).isZero();
        assertThat(flow.inputTokens()).isZero();
        assertThat(flow.cost()).containsEntry("USD", "0.5");
        assertThat(live.buckets().getLast().failed()).isEqualTo(1);
        assertThat(live.p50Millis()).isNull();
    }

    @Test
    void redactionsAreSummedIntoTheFlow() {
        Redactions redactions = new Redactions(Map.of("email", 2, "phone", 1), LiveRecords.HASH);
        window.add(LiveRecords.intent("c1", T, "app", List.of(), Decision.ALLOW, redactions));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).redacted()).isEqualTo(3);
        assertThat(live.calls().getFirst().redacted()).containsEntry("email", 2);
    }

    @Test
    void anOutcomeWithoutItsIntentChangesNothing() {
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));

        Live live = window.snapshot(T);

        assertThat(live.flows()).isEmpty();
        assertThat(sent(live)).isZero();
        assertThat(live.p50Millis()).isNull();
    }

    @Test
    void flowsAreForgottenAfterFifteenMinutesWhileBucketsStayForTheHour() {
        window.add(allowed("c1", T));
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));

        Live later = window.snapshot(T.plus(Duration.ofMinutes(16)));

        assertThat(later.flows()).isEmpty();
        assertThat(sent(later)).isEqualTo(1);
        assertThat(later.p50Millis()).isNull();
        assertThat(sent(window.snapshot(T.plus(Duration.ofMinutes(61))))).isZero();
    }

    @Test
    void aHundredCallsOf100MillisGiveAPercentileOf128() {
        for (int i = 0; i < 100; i++) {
            window.add(allowed("c" + i, T));
            window.add(outcome("c" + i, T, Outcome.Result.RESPONDED, 100));
        }

        Live live = window.snapshot(T);

        assertThat(live.p50Millis()).isEqualTo(128L);
        assertThat(live.p95Millis()).isEqualTo(128L);
    }

    @Test
    void percentilesFollowTheCountsAcrossSteps() {
        for (int i = 0; i < 90; i++) {
            window.add(allowed("a" + i, T));
            window.add(outcome("a" + i, T, Outcome.Result.RESPONDED, 10));
        }
        for (int i = 0; i < 10; i++) {
            window.add(allowed("b" + i, T));
            window.add(outcome("b" + i, T, Outcome.Result.RESPONDED, 1000));
        }

        Live live = window.snapshot(T);

        assertThat(live.p50Millis()).isEqualTo(16L);
        assertThat(live.p95Millis()).isEqualTo(1024L);
    }

    @Test
    void bucketsAreFiveSecondsOldestFirstAndNeverMoreThan720() {
        window.add(allowed("c1", T));

        Live live = window.snapshot(T.plusSeconds(3));

        assertThat(live.buckets()).hasSize(720);
        assertThat(live.buckets().getLast().start()).isEqualTo(T.getEpochSecond());
        assertThat(live.buckets().getFirst().start()).isEqualTo(T.getEpochSecond() - 719 * 5);
        assertThat(live.buckets().stream().allMatch(bucket -> bucket.start() % 5 == 0))
                .isTrue();
    }

    @Test
    void bucketsStartAtTheWindowStartWhenItIsRecent() {
        LiveWindow fresh = new LiveWindow(T.minusSeconds(12));

        Live live = fresh.snapshot(T);

        assertThat(live.since()).isEqualTo(T.minusSeconds(12));
        assertThat(live.buckets())
                .extracting(Bucket::start)
                .containsExactly(
                        T.getEpochSecond() - 15, T.getEpochSecond() - 10, T.getEpochSecond() - 5, T.getEpochSecond());
    }

    @Test
    void aRecordOlderThanTheHourChangesNothing() {
        window.add(allowed("c1", T));
        window.add(allowed("old", T.minus(Duration.ofHours(2))));
        window.add(outcome("old", T.minus(Duration.ofHours(2)), Outcome.Result.RESPONDED, 10));

        Live live = window.snapshot(T);

        assertThat(live.calls()).hasSize(1);
        assertThat(sent(live)).isZero();
    }

    @Test
    void flowsBeyondTheKeyLimitAreCountedUnderOther() {
        for (int i = 0; i < 2100; i++) {
            window.add(intent("c" + i, T, "t%04d".formatted(i), List.of(), deny(Refusal.POLICY_DENIED)));
        }

        Live live = window.snapshot(T);

        assertThat(live.flows()).hasSize(2000);
        Flow other = live.flows().stream()
                .filter(flow -> flow.tenant().equals("other"))
                .findFirst()
                .orElseThrow();
        assertThat(other.model()).isEqualTo("other");
        assertThat(other.provider()).isEqualTo("other");
        assertThat(other.refused()).containsExactly(Map.entry("policy_denied", 101L));
        assertThat(live.flows().stream()
                        .mapToLong(flow -> flow.refused().get("policy_denied"))
                        .sum())
                .isEqualTo(2100);
    }

    @Test
    void guardCountsBeyondTheKeyLimitAreCountedUnderOther() {
        for (int i = 0; i < 2100; i++) {
            window.add(intent(
                    "c" + i, T, "app", List.of(step("g%04d".formatted(i), Decision.ALLOW, false)), Decision.ALLOW));
        }

        Live live = window.snapshot(T);

        assertThat(live.guards()).hasSize(2000);
        GuardCount other = live.guards().stream()
                .filter(guard -> guard.guard().equals("other"))
                .findFirst()
                .orElseThrow();
        assertThat(other.tenant()).isEqualTo("other");
        assertThat(other.allowed()).isEqualTo(101);
    }

    @Test
    void theNewest200CallsAreKeptNewestFirst() {
        for (int i = 0; i < 201; i++) {
            window.add(allowed("c%03d".formatted(i), T.plusMillis(i)));
        }

        Live live = window.snapshot(T.plusSeconds(1));

        assertThat(live.calls()).hasSize(200);
        assertThat(live.calls().getFirst().id()).isEqualTo("c200");
        assertThat(live.calls().getLast().id()).isEqualTo("c001");
    }

    @Test
    void anOutcomeAppearsOnItsCall() {
        window.add(allowed("c1", T));
        assertThat(window.snapshot(T).calls().getFirst().outcome()).isNull();
        window.add(outcome("c1", T.plusSeconds(1), Outcome.Result.RESPONDED, 250));

        LiveViews.CallView call = window.snapshot(T.plusSeconds(1)).calls().getFirst();

        LiveViews.OutcomeView outcome = Objects.requireNonNull(call.outcome());
        assertThat(outcome.result()).isEqualTo("responded");
        assertThat(outcome.millis()).isEqualTo(250);
        assertThat(Objects.requireNonNull(outcome.tokens()).input()).isEqualTo(10);
        assertThat(outcome.cost().amount()).isEqualTo("0.5");
        assertThat(outcome.cost().currency()).isEqualTo("USD");
        assertThat(call.endpoint()).isEqualTo("openai");
        assertThat(call.attributed()).isFalse();
    }

    @Test
    void aCallShowsItsStepsInMicroseconds() {
        window.add(intent("c1", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));

        List<LiveViews.StepView> steps = window.snapshot(T).calls().getFirst().steps();

        assertThat(steps).hasSize(1);
        assertThat(steps.getFirst().micros()).isEqualTo(2);
        assertThat(steps.getFirst().allowed()).isTrue();
    }

    @Test
    void onlyTenThousandIntentsWaitForAnOutcome() {
        for (int i = 0; i <= 10_000; i++) {
            window.add(allowed("c%05d".formatted(i), T));
        }
        window.add(outcome("c00000", T, Outcome.Result.RESPONDED, 10));
        window.add(outcome("c10000", T, Outcome.Result.RESPONDED, 10));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).sent()).isEqualTo(1);
    }

    @Test
    void aSealBecomesTheLastSeal() {
        window.add(
                new Seal(3, 7, T, LiveRecords.HASH, LiveRecords.HASH, "0123456789abcdef", "fedcba9876543210", "sig"));

        LiveViews.SealView view = Objects.requireNonNull(window.snapshot(T).lastSeal());

        assertThat(view.batch()).isEqualTo(3);
        assertThat(view.records()).isEqualTo(7);
        assertThat(view.key()).isEqualTo("0123456789abcdef");
        assertThat(view.hmacKey()).isEqualTo("fedcba9876543210");
    }

    @Test
    void skippedShowsForAMinuteAfterASkip() {
        assertThat(window.snapshot(T).skipped()).isFalse();
        window.skipped(T);
        assertThat(window.snapshot(T).skipped()).isTrue();
        assertThat(window.snapshot(T.plusSeconds(59)).skipped()).isTrue();
        assertThat(window.snapshot(T.plusSeconds(60)).skipped()).isFalse();
    }

    @Test
    void flowsAreSortedByTenantModelAndProvider() {
        window.add(intent("c1", T, "zeta", List.of(), deny(Refusal.POLICY_DENIED)));
        window.add(intent("c2", T, "alpha", List.of(), deny(Refusal.POLICY_DENIED)));

        assertThat(window.snapshot(T).flows()).extracting(Flow::tenant).containsExactly("alpha", "zeta");
    }
}
