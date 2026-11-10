package io.github.jamalakhras.tollgate.core.limit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.limit.CircuitBreakers.Change;
import io.github.jamalakhras.tollgate.core.limit.CircuitBreakers.Open;
import io.github.jamalakhras.tollgate.core.limit.CircuitBreakers.Pass;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CircuitBreakersTest {

    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private final CircuitBreakers breakers = new CircuitBreakers(now::get);

    private void wait(Duration duration) {
        now.addAndGet(duration.toNanos());
    }

    private Pass passed(String provider) {
        CircuitBreakers.Result result = breakers.admit(provider);
        assertThat(result).isInstanceOf(Pass.class);
        return (Pass) result;
    }

    private Open open(String provider) {
        CircuitBreakers.Result result = breakers.admit(provider);
        assertThat(result).isInstanceOf(Open.class);
        return (Open) result;
    }

    private void failTimes(String provider, int times) {
        for (int i = 0; i < times; i++) {
            passed(provider).failed();
        }
    }

    @Test
    void opensAfterFiveFailuresInARow() {
        failTimes("ollama", 4);
        passed("ollama");

        assertThat(passed("ollama").failed()).isEqualTo(Change.OPENED);
        assertThat(open("ollama").retryAfterSeconds()).isEqualTo(30);
        wait(Duration.ofMillis(20_500));
        assertThat(open("ollama").retryAfterSeconds()).isEqualTo(10);
    }

    @Test
    void anAnswerResetsTheCount() {
        failTimes("ollama", 4);
        assertThat(passed("ollama").succeeded()).isEqualTo(Change.NONE);
        failTimes("ollama", 4);

        passed("ollama");
    }

    @Test
    void letsOneCallTryOnceTheBreakerHasWaited() {
        failTimes("ollama", 5);
        wait(CircuitBreakers.OPEN);

        Pass trial = passed("ollama");
        assertThat(open("ollama").retryAfterSeconds()).isEqualTo(1);
        assertThat(trial.succeeded()).isEqualTo(Change.CLOSED);
        passed("ollama");
    }

    @Test
    void staysOpenWhenTheTrialFails() {
        failTimes("ollama", 5);
        wait(CircuitBreakers.OPEN);

        assertThat(passed("ollama").failed()).isEqualTo(Change.NONE);
        assertThat(open("ollama").retryAfterSeconds()).isEqualTo(30);
        wait(CircuitBreakers.OPEN);
        passed("ollama");
    }

    @Test
    void letsAnotherCallTryWhenATrialEndsWithoutAnAnswer() {
        failTimes("ollama", 5);
        wait(CircuitBreakers.OPEN);

        passed("ollama").close();

        Pass trial = passed("ollama");
        open("ollama");
        trial.close();
    }

    @Test
    void ignoresLateFailuresOnceOpenAndClosesOnALateAnswer() {
        Pass late = passed("ollama");
        Pass answered = passed("ollama");
        failTimes("ollama", 5);

        assertThat(late.failed()).isEqualTo(Change.NONE);
        assertThat(open("ollama").retryAfterSeconds()).isEqualTo(30);
        assertThat(answered.succeeded()).isEqualTo(Change.CLOSED);
        passed("ollama");
    }

    @Test
    void countsEachReportOnce() {
        Pass pass = passed("ollama");
        pass.failed();
        pass.failed();
        pass.close();
        failTimes("ollama", 3);

        passed("ollama");
        assertThat(pass.succeeded()).isEqualTo(Change.NONE);
    }

    @Test
    void keepsEachProviderSeparate() {
        failTimes("ollama", 5);

        open("ollama");
        passed("anthropic");
    }
}
