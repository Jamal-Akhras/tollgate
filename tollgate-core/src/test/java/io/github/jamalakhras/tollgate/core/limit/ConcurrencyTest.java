package io.github.jamalakhras.tollgate.core.limit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.limit.Concurrency.Slot;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    private final Concurrency concurrency = new Concurrency();

    private Slot taken(String name, Long limit) {
        return concurrency.take(name, limit).orElseThrow();
    }

    @Test
    void refusesASlotWhileTheLimitIsInUse() {
        Slot first = taken("ollama", 2L);
        Slot second = taken("ollama", 2L);

        assertThat(concurrency.take("ollama", 2L)).isEmpty();
        second.close();
        Slot third = taken("ollama", 2L);
        assertThat(concurrency.take("ollama", 2L)).isEmpty();
        first.close();
        third.close();
    }

    @Test
    void freesASlotOnlyOnce() {
        Slot first = taken("ollama", 2L);
        Slot second = taken("ollama", 2L);
        first.close();
        first.close();

        Slot third = taken("ollama", 2L);
        assertThat(concurrency.take("ollama", 2L)).isEmpty();
        second.close();
        third.close();
    }

    @Test
    void countsEachNameSeparately() {
        Slot ollama = taken("ollama", 1L);

        Slot anthropic = taken("anthropic", 1L);
        assertThat(concurrency.take("ollama", 1L)).isEmpty();
        ollama.close();
        anthropic.close();
    }

    @Test
    void appliesAChangedLimitToTheCallsAlreadyInFlight() {
        Slot first = taken("ollama", 3L);
        Slot second = taken("ollama", 3L);

        assertThat(concurrency.take("ollama", 2L)).isEmpty();
        first.close();
        taken("ollama", 2L).close();
        second.close();
    }

    @Test
    void neverRefusesWithoutALimitButStillCounts() {
        List<Slot> slots = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            slots.add(concurrency.take("ollama", null).orElseThrow());
        }

        assertThat(concurrency.inFlight("ollama")).isEqualTo(100);
        slots.forEach(Slot::close);
        assertThat(concurrency.inFlight("ollama")).isZero();
        assertThat(concurrency.inFlight("anthropic")).isZero();
    }

    @Test
    void holdsTheLimitUnderContention() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            tasks.add(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int call = 0; call < 200; call++) {
                    concurrency.take("ollama", 4L).ifPresent(slot -> {
                        most.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                        inFlight.decrementAndGet();
                        slot.close();
                    });
                }
            });
        }
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running =
                    tasks.stream().<Future<?>>map(threads::submit).toList();
            start.countDown();
            for (Future<?> task : running) {
                task.get();
            }
        }

        assertThat(most.get()).isBetween(1, 4);
        for (int i = 0; i < 4; i++) {
            taken("ollama", 4L);
        }
        assertThat(concurrency.take("ollama", 4L)).isEmpty();
    }
}
