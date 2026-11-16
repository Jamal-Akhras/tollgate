package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class OneAtATimeTest {

    private final OneAtATime reads = new OneAtATime();

    @Test
    void refusesASecondReadWhileOneRuns() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try {
                return reads.run(() -> {
                    started.countDown();
                    release.await();
                    return "done";
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        started.await();

        assertThatThrownBy(() -> reads.run(() -> "second")).isInstanceOf(OneAtATime.Busy.class);

        release.countDown();
        assertThat(first.get()).isEqualTo("done");
    }

    @Test
    void letsTheNextReadInAfterOneEndsEvenWhenItFails() throws Exception {
        assertThatThrownBy(() -> reads.run(() -> {
                    throw new IllegalStateException("broken");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(reads.run(() -> "next")).isEqualTo("next");
    }
}
