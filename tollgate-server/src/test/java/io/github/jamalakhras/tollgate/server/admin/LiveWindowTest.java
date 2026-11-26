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
import io.github.jamalakhras.tollgate.server.admin.LiveAnswer.Answer;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Bucket;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Flow;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.GuardCount;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Latency;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Percentiles;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Totals;
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

    private static Answer answer(Live live) {
        return LiveAnswer.of(live, 15, 5);
    }

    private static Flow onlyFlow(Live live) {
        assertThat(answer(live).flows()).hasSize(1);
        return answer(live).flows().getFirst();
    }

    private static long sent(Live live) {
        return answer(live).buckets().stream().mapToLong(Bucket::sent).sum();
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
        Bucket bucket = answer(live).buckets().getLast();
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
        GuardCount guard = answer(live).guards().getFirst();
        assertThat(guard.guard()).isEqualTo("tool_names");
        assertThat(guard.refused()).isEqualTo(1);
        assertThat(guard.allowed()).isZero();
        assertThat(guard.lastRefused()).isEqualTo(T);
        assertThat(answer(live).buckets().getLast().refused()).isEqualTo(1);
    }

    @Test
    void aMonitoredStepIsCountedAsMonitoredAndLetsTheCallThrough() {
        window.add(
                intent("c1", T, "app", List.of(step("tool_names", deny(Refusal.POLICY_DENIED), true)), Decision.ALLOW));

        Live live = window.snapshot(T);

        GuardCount guard = answer(live).guards().getFirst();
        assertThat(guard.monitored()).isEqualTo(1);
        assertThat(guard.refused()).isZero();
        assertThat(guard.lastRefused()).isNull();
        assertThat(answer(live).buckets().getLast().refused()).isZero();
    }

    @Test
    void aBudgetRefusalRefusesNoStep() {
        window.add(intent(
                "c1", T, "app", List.of(step("data_class", Decision.ALLOW, false)), deny(Refusal.BUDGET_EXCEEDED)));

        Live live = window.snapshot(T);

        assertThat(onlyFlow(live).refused()).containsExactly(Map.entry("budget_exceeded", 1L));
        GuardCount guard = answer(live).guards().getFirst();
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

        assertThat(answer(live).flows()).isEmpty();
        assertThat(answer(live).buckets().getLast().sent()).isZero();
        assertThat(answer(live).buckets().getLast().failed()).isZero();
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
        assertThat(answer(live).buckets().getLast().failed()).isEqualTo(1);
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

        assertThat(answer(live).flows()).isEmpty();
        assertThat(sent(live)).isZero();
        assertThat(live.p50Millis()).isNull();
    }

    @Test
    void flowsAndBucketsFollowTheRequestedMinutes() {
        window.add(allowed("c1", T));
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));
        Live later = window.snapshot(T.plus(Duration.ofMinutes(16)));

        assertThat(LiveAnswer.of(later, 15, 5).flows()).isEmpty();
        assertThat(LiveAnswer.of(later, 15, 5).totals().sent()).isZero();
        assertThat(LiveAnswer.of(later, 20, 5).flows()).hasSize(1);
        assertThat(LiveAnswer.of(later, 20, 5).totals().sent()).isEqualTo(1);
        assertThat(later.p50Millis()).isNull();
    }

    @Test
    void flowsAndGuardsAreKeptForAnHourAndBucketsForTwo() {
        window.add(intent("c1", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));

        Live almost = window.snapshot(T.plus(Duration.ofMinutes(59)));
        Live gone = window.snapshot(T.plus(Duration.ofMinutes(61)));

        assertThat(LiveAnswer.of(almost, 60, 5).flows()).hasSize(1);
        assertThat(LiveAnswer.of(almost, 60, 5).guards()).hasSize(1);
        assertThat(LiveAnswer.of(gone, 60, 5).flows()).isEmpty();
        assertThat(LiveAnswer.of(gone, 60, 5).totals().sent()).isZero();
        assertThat(Objects.requireNonNull(LiveAnswer.of(gone, 60, 5).previous()).sent())
                .isEqualTo(1);
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
    void bucketsAreFiveSecondsOldestFirstAndAnHourHasNoMoreThan720() {
        window.add(allowed("c1", T));

        Answer hour = LiveAnswer.of(window.snapshot(T.plusSeconds(3)), 60, 5);

        assertThat(hour.buckets()).hasSize(720);
        assertThat(hour.buckets().getLast().start()).isEqualTo(T.getEpochSecond());
        assertThat(hour.buckets().getFirst().start()).isEqualTo(T.getEpochSecond() - 719 * 5);
        assertThat(hour.buckets().stream().allMatch(bucket -> bucket.start() % 5 == 0))
                .isTrue();
        assertThat(hour.since()).isEqualTo(Instant.ofEpochSecond(T.getEpochSecond() - 719 * 5));
        assertThat(answer(window.snapshot(T)).buckets()).hasSize(180);
    }

    @Test
    void bucketsStartAtTheWindowStartWhenItIsRecent() {
        LiveWindow fresh = new LiveWindow(T.minusSeconds(12));

        Answer answer = answer(fresh.snapshot(T));

        assertThat(answer.since()).isEqualTo(T.minusSeconds(15));
        assertThat(answer.buckets())
                .extracting(Bucket::start)
                .containsExactly(
                        T.getEpochSecond() - 15, T.getEpochSecond() - 10, T.getEpochSecond() - 5, T.getEpochSecond());
    }

    @Test
    void aRecordOlderThanTwoHoursChangesNothingButOneInsideCounts() {
        Decision denial = deny(Refusal.POLICY_DENIED);
        window.add(allowed("c1", T));
        window.add(intent("old", T.minus(Duration.ofHours(2)), "app", List.of(), denial));
        window.add(intent("inside", T.minus(Duration.ofMinutes(119)), "app", List.of(), denial));

        Live live = window.snapshot(T);

        assertThat(live.calls()).extracting(LiveViews.CallView::id).containsExactly("inside", "c1");
        assertThat(live.buckets()).hasSize(1);
        assertThat(live.buckets().firstKey())
                .isEqualTo(T.minus(Duration.ofMinutes(119)).getEpochSecond());
    }

    @Test
    void flowsBeyondTheKeyLimitAreCountedUnderOther() {
        for (int i = 0; i < 600; i++) {
            window.add(intent("c" + i, T, "t%04d".formatted(i), List.of(), deny(Refusal.POLICY_DENIED)));
        }

        Live live = window.snapshot(T);

        assertThat(answer(live).flows()).hasSize(500);
        Flow other = answer(live).flows().stream()
                .filter(flow -> flow.tenant().equals("other"))
                .findFirst()
                .orElseThrow();
        assertThat(other.model()).isEqualTo("other");
        assertThat(other.provider()).isEqualTo("other");
        assertThat(other.refused()).containsExactly(Map.entry("policy_denied", 101L));
        assertThat(answer(live).flows().stream()
                        .mapToLong(flow -> flow.refused().get("policy_denied"))
                        .sum())
                .isEqualTo(600);
    }

    @Test
    void guardCountsBeyondTheKeyLimitAreCountedUnderOther() {
        for (int i = 0; i < 600; i++) {
            window.add(intent(
                    "c" + i, T, "app", List.of(step("g%04d".formatted(i), Decision.ALLOW, false)), Decision.ALLOW));
        }

        Live live = window.snapshot(T);

        assertThat(answer(live).guards()).hasSize(500);
        GuardCount other = answer(live).guards().stream()
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

        assertThat(answer(window.snapshot(T)).flows()).extracting(Flow::tenant).containsExactly("alpha", "zeta");
    }

    private static Outcome outcome(String id, Instant time, int status, long millis) {
        return LiveRecords.outcome(id, time, Outcome.Result.RESPONDED, status, millis, new Money(500_000_000, USD));
    }

    private void respond(String id, Instant time, int status, long millis) {
        window.add(allowed(id, time));
        window.add(outcome(id, time, status, millis));
    }

    @Test
    void statusClassesAreCountedOnBucketsAndFlowsAsSubsetsOfSent() {
        respond("a", T, 200, 10);
        respond("b", T, 429, 10);
        respond("c", T, 404, 10);
        respond("d", T, 503, 10);
        window.add(allowed("e", T));
        window.add(LiveRecords.outcome("e", T, Outcome.Result.TIMED_OUT, 504, 10, new Money(0, USD)));

        Live live = window.snapshot(T);

        Bucket bucket = answer(live).buckets().getLast();
        assertThat(bucket.sent()).isEqualTo(4);
        assertThat(bucket.clientErrors()).isEqualTo(2);
        assertThat(bucket.serverErrors()).isEqualTo(1);
        assertThat(bucket.failed()).isEqualTo(1);
        Flow flow = onlyFlow(live);
        assertThat(flow.sent()).isEqualTo(4);
        assertThat(flow.clientErrors()).isEqualTo(2);
        assertThat(flow.serverErrors()).isEqualTo(1);
        assertThat(answer(live).totals().clientErrors()).isEqualTo(2);
        assertThat(answer(live).totals().serverErrors()).isEqualTo(1);
    }

    @Test
    void aCoarserBucketIsTheSumOfTheFiveSecondOnesFromAMultipleOfTheStep() {
        respond("a", T.plusSeconds(1), 200, 10);
        respond("b", T.plusSeconds(7), 500, 10);
        respond("c", T.plusSeconds(14), 200, 10);
        respond("d", T.plusSeconds(16), 200, 10);
        Live live = window.snapshot(T.plusSeconds(20));

        List<Bucket> fifteen = LiveAnswer.of(live, 1, 15).buckets();

        assertThat(fifteen)
                .extracting(Bucket::start)
                .containsExactly(
                        T.getEpochSecond() - 30, T.getEpochSecond() - 15, T.getEpochSecond(), T.getEpochSecond() + 15);
        assertThat(fifteen).extracting(Bucket::sent).containsExactly(0L, 0L, 3L, 1L);
        assertThat(fifteen).extracting(Bucket::serverErrors).containsExactly(0L, 0L, 1L, 0L);
        assertThat(fifteen.get(2).cost()).containsEntry("USD", "1.5");
        assertThat(fifteen.get(2).inputTokens()).isEqualTo(30);
        List<Bucket> minute = LiveAnswer.of(live, 3, 60).buckets();
        assertThat(minute)
                .extracting(Bucket::start)
                .containsExactly(T.getEpochSecond() - 120, T.getEpochSecond() - 60, T.getEpochSecond());
        assertThat(minute.getLast().sent()).isEqualTo(4);
    }

    @Test
    void aCoarserBucketTakesItsPercentilesFromTheSummedHistogram() {
        for (int i = 0; i < 90; i++) {
            respond("a" + i, T.plusSeconds(1), 200, 10);
        }
        for (int i = 0; i < 10; i++) {
            respond("b" + i, T.plusSeconds(6), 200, 1000);
        }
        Live live = window.snapshot(T.plusSeconds(10));

        List<Bucket> fives = LiveAnswer.of(live, 1, 5).buckets();
        Bucket summed = LiveAnswer.of(live, 1, 15).buckets().getLast();

        assertThat(fives.get(fives.size() - 3).p95Millis()).isEqualTo(16L);
        assertThat(fives.get(fives.size() - 2).p95Millis()).isEqualTo(1024L);
        assertThat(summed.p50Millis()).isEqualTo(16L);
        assertThat(summed.p95Millis()).isEqualTo(1024L);
        assertThat(summed.sent()).isEqualTo(100);
    }

    @Test
    void theFirstCoarseBucketIsPartialAtTheInstanceStart() {
        LiveWindow fresh = new LiveWindow(T.plusSeconds(40));
        fresh.add(allowed("a", T.plusSeconds(41)));
        fresh.add(outcome("a", T.plusSeconds(41), Outcome.Result.RESPONDED, 10));

        Answer answer = LiveAnswer.of(fresh.snapshot(T.plusSeconds(100)), 15, 60);

        assertThat(answer.buckets())
                .extracting(Bucket::start)
                .containsExactly(T.getEpochSecond(), T.getEpochSecond() + 60);
        assertThat(answer.buckets().getFirst().sent()).isEqualTo(1);
        assertThat(answer.since()).isEqualTo(T);
    }

    @Test
    void flowsAndGuardsCoverTheRequestedMinutes() {
        window.add(intent("old", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("old", T, Outcome.Result.RESPONDED, 10));
        window.add(
                intent("new", T.plusSeconds(300), "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("new", T.plusSeconds(300), Outcome.Result.RESPONDED, 10));
        Live live = window.snapshot(T.plusSeconds(330));

        Answer one = LiveAnswer.of(live, 1, 5);
        Answer six = LiveAnswer.of(live, 6, 5);

        assertThat(one.flows()).extracting(Flow::sent).containsExactly(1L);
        assertThat(one.guards()).extracting(GuardCount::allowed).containsExactly(1L);
        assertThat(six.flows()).extracting(Flow::sent).containsExactly(2L);
        assertThat(six.guards()).extracting(GuardCount::allowed).containsExactly(2L);
        assertThat(LiveAnswer.of(live, 60, 5).flows()).extracting(Flow::sent).containsExactly(2L);
    }

    @Test
    void totalsSumTheSpanAndPreviousTheMinutesBeforeIt() {
        respond("a", T.minusSeconds(130), 200, 10);
        respond("b", T.minusSeconds(70), 429, 100);
        respond("c", T.minusSeconds(10), 200, 100);
        respond("d", T, 500, 1000);
        window.add(intent("r", T, "app", List.of(), deny(Refusal.POLICY_DENIED)));
        Live live = window.snapshot(T.plusSeconds(1));

        Answer answer = LiveAnswer.of(live, 2, 5);

        Totals totals = answer.totals();
        assertThat(totals.seconds()).isEqualTo(61);
        assertThat(totals.sent()).isEqualTo(2);
        assertThat(totals.refused()).isEqualTo(1);
        assertThat(totals.clientErrors()).isZero();
        assertThat(totals.serverErrors()).isEqualTo(1);
        assertThat(totals.inputTokens()).isEqualTo(20);
        assertThat(totals.outputTokens()).isEqualTo(10);
        assertThat(totals.cost()).containsExactly(Map.entry("USD", "1"));
        assertThat(totals.p50Millis()).isEqualTo(128L);
        assertThat(totals.p95Millis()).isEqualTo(1024L);
        Totals previous = Objects.requireNonNull(answer.previous());
        assertThat(previous.seconds()).isEqualTo(120);
        assertThat(previous.sent()).isEqualTo(2);
        assertThat(previous.clientErrors()).isEqualTo(1);
        assertThat(previous.p50Millis()).isEqualTo(16L);
    }

    @Test
    void previousIsNullUntilTheInstanceWasRunningAtTheStartOfTheEarlierSpan() {
        LiveWindow young = new LiveWindow(T.minusSeconds(30));

        assertThat(LiveAnswer.of(young.snapshot(T), 1, 5).previous()).isNull();
        assertThat(LiveAnswer.of(young.snapshot(T.plusSeconds(30)), 1, 5).previous())
                .isNull();
        assertThat(LiveAnswer.of(young.snapshot(T.plusSeconds(60)), 1, 5).previous())
                .isNotNull();
        assertThat(LiveAnswer.of(young.snapshot(T.plusSeconds(60)), 2, 5).previous())
                .isNull();
    }

    @Test
    void latencyIsGroupedByProviderTenantAndModelFromTheirOwnHistograms() {
        window.add(intent("a", T, "alpha", List.of(), Decision.ALLOW));
        window.add(outcome("a", T, Outcome.Result.RESPONDED, 10));
        window.add(intent("b", T, "beta", List.of(), Decision.ALLOW));
        window.add(outcome("b", T, Outcome.Result.RESPONDED, 1000));
        window.add(intent("c", T, "beta", List.of(), deny(Refusal.POLICY_DENIED)));
        window.add(intent("d", T, "gamma", List.of(), Decision.ALLOW));
        window.add(outcome("d", T, Outcome.Result.TIMED_OUT, 5000));

        Latency latency = answer(window.snapshot(T)).latency();

        assertThat(latency.tenants()).containsOnlyKeys("alpha", "beta");
        assertThat(latency.tenants().get("alpha")).isEqualTo(new Percentiles(16, 16));
        assertThat(latency.tenants().get("beta")).isEqualTo(new Percentiles(1024, 1024));
        assertThat(latency.providers()).containsOnlyKeys("ollama");
        assertThat(latency.providers().get("ollama")).isEqualTo(new Percentiles(16, 1024));
        assertThat(latency.models()).containsOnlyKeys("qwen3");
    }

    @Test
    void theTopLevelPercentilesStayTheLastFiveMinutes() {
        respond("a", T.minusSeconds(400), 200, 1000);
        respond("b", T.minusSeconds(10), 200, 10);
        Live live = window.snapshot(T);

        assertThat(live.p50Millis()).isEqualTo(16L);
        assertThat(live.p95Millis()).isEqualTo(16L);
        assertThat(answer(live).totals().p95Millis()).isEqualTo(1024L);
    }

    @Test
    void aLateRecordForAnEarlierMinuteStillCountsAfterASnapshot() {
        respond("a", T.plusSeconds(70), 200, 10);
        window.snapshot(T.plusSeconds(80));
        respond("b", T.plusSeconds(10), 200, 10);

        Live live = window.snapshot(T.plusSeconds(81));

        assertThat(onlyFlow(live).sent()).isEqualTo(2);
        assertThat(answer(live).totals().sent()).isEqualTo(2);
    }

    @Test
    void aPublishedSnapshotDoesNotChangeWhenTheReaderAddsMore() {
        window.add(intent("c1", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));
        Live first = window.snapshot(T.plusSeconds(1));
        Answer before = LiveAnswer.of(first, 15, 5);
        Answer beforeMinutes = LiveAnswer.of(first, 15, 60);

        window.add(intent("c2", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("c2", T, 503, 2000));

        assertThat(LiveAnswer.of(first, 15, 5)).isEqualTo(before);
        assertThat(LiveAnswer.of(first, 15, 60)).isEqualTo(beforeMinutes);
        Answer after = LiveAnswer.of(window.snapshot(T.plusSeconds(2)), 15, 5);
        assertThat(after.totals().sent()).isEqualTo(2);
        assertThat(after.totals().serverErrors()).isEqualTo(1);
        assertThat(after.flows().getFirst().sent()).isEqualTo(2);
        assertThat(after.guards().getFirst().allowed()).isEqualTo(2);
        assertThat(before.totals().sent()).isEqualTo(1);
        assertThat(before.flows().getFirst().sent()).isEqualTo(1);
        assertThat(before.guards().getFirst().allowed()).isEqualTo(1);
    }

    @Test
    void oldMinutesAndBucketsAreEvictedWhenALaterRecordArrives() {
        window.add(intent("c1", T, "app", List.of(step("size", Decision.ALLOW, false)), Decision.ALLOW));
        window.add(outcome("c1", T, Outcome.Result.RESPONDED, 100));
        Instant later = T.plus(Duration.ofHours(3));
        assertThat(window.snapshot(T).buckets()).containsKey(T.getEpochSecond());

        window.add(allowed("c2", later));
        Live live = window.snapshot(later);

        assertThat(live.buckets()).doesNotContainKey(T.getEpochSecond());
        assertThat(live.flows()).doesNotContainKey(T.getEpochSecond() / 60);
        assertThat(live.guards()).doesNotContainKey(T.getEpochSecond() / 60);
        assertThat(LiveAnswer.of(live, 60, 5).totals().sent()).isZero();
        assertThat(Objects.requireNonNull(LiveAnswer.of(live, 60, 5).previous()).sent())
                .isZero();
    }

    @Test
    void previousServesAFullHourOfMinutesWithMinuteSteps() {
        respond("a", T.minus(Duration.ofMinutes(90)), 200, 10);
        Live live = window.snapshot(T.plusSeconds(30));

        Answer answer = LiveAnswer.of(live, 60, 60);

        assertThat(answer.buckets()).hasSize(60);
        assertThat(answer.totals().sent()).isZero();
        assertThat(answer.totals().seconds()).isEqualTo(59 * 60 + 30);
        Totals previous = Objects.requireNonNull(answer.previous());
        assertThat(previous.sent()).isEqualTo(1);
        assertThat(previous.seconds()).isEqualTo(3600);
    }

    @Test
    void aClockThatMovesBackwardsGivesNoBucketsAndNoPrevious() {
        LiveWindow fresh = new LiveWindow(T);
        fresh.add(allowed("a", T));
        fresh.add(outcome("a", T, Outcome.Result.RESPONDED, 10));

        Answer answer = answer(fresh.snapshot(T.minusSeconds(100)));

        assertThat(answer.buckets()).isEmpty();
        assertThat(answer.previous()).isNull();
        assertThat(answer.totals().seconds()).isZero();
    }

    @Test
    void totalsAndFlowsCoverTheSameSpanWhateverTheStep() {
        respond("a", T.minusSeconds(100), 200, 10);
        respond("b", T.minusSeconds(50), 429, 10);
        respond("c", T.minusSeconds(5), 503, 10);
        respond("d", T.plusSeconds(1), 200, 10);
        Live live = window.snapshot(T.plusSeconds(2));

        for (int step : new int[] {5, 15, 60}) {
            Answer answer = LiveAnswer.of(live, 2, step);
            assertThat(answer.totals().sent()).isEqualTo(3);
            assertThat(answer.flows().stream().mapToLong(Flow::sent).sum())
                    .isEqualTo(answer.totals().sent());
            assertThat(answer.flows().stream().mapToLong(Flow::clientErrors).sum())
                    .isEqualTo(answer.totals().clientErrors());
            assertThat(answer.flows().stream().mapToLong(Flow::serverErrors).sum())
                    .isEqualTo(answer.totals().serverErrors());
            assertThat(answer.totals().seconds()).isEqualTo(62);
        }
    }

    @Test
    void secondsAreTheSpanForAYoungInstanceAndAFullWindow() {
        LiveWindow young = new LiveWindow(T.minusSeconds(20));

        assertThat(LiveAnswer.of(young.snapshot(T.plusSeconds(10)), 5, 15)
                        .totals()
                        .seconds())
                .isEqualTo(30);
        assertThat(LiveAnswer.of(window.snapshot(T.plusSeconds(10)), 5, 15)
                        .totals()
                        .seconds())
                .isEqualTo(4 * 60 + 10);
        assertThat(Objects.requireNonNull(LiveAnswer.of(window.snapshot(T.plusSeconds(10)), 5, 15)
                                .previous())
                        .seconds())
                .isEqualTo(300);
    }

    @Test
    void clientClosedIsASubsetOfFailed() {
        window.add(allowed("a", T));
        window.add(LiveRecords.outcome("a", T, Outcome.Result.CLIENT_CLOSED, 200, 10, new Money(0, USD)));
        window.add(allowed("b", T));
        window.add(LiveRecords.outcome("b", T, Outcome.Result.TIMED_OUT, 504, 10, new Money(0, USD)));
        Live live = window.snapshot(T);

        Bucket bucket = answer(live).buckets().getLast();
        assertThat(bucket.failed()).isEqualTo(2);
        assertThat(bucket.clientClosed()).isEqualTo(1);
        assertThat(bucket.sent()).isZero();
        assertThat(onlyFlow(live).failed()).isEqualTo(2);
        assertThat(onlyFlow(live).clientClosed()).isEqualTo(1);
        assertThat(answer(live).totals().failed()).isEqualTo(2);
        assertThat(answer(live).totals().clientClosed()).isEqualTo(1);
    }
}
