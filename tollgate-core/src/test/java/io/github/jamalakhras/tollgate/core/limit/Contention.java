package io.github.jamalakhras.tollgate.core.limit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class Contention {

    private Contention() {}

    static void run(int threads, Runnable body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                running.add(executor.submit(() -> {
                    start.await();
                    body.run();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get();
            }
        }
    }
}
