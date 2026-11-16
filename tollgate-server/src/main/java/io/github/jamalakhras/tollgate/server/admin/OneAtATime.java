package io.github.jamalakhras.tollgate.server.admin;

import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

@Component
class OneAtATime {

    private final Semaphore running = new Semaphore(1);

    <T> T run(Callable<T> read) throws Exception {
        if (!running.tryAcquire()) {
            throw new Busy();
        }
        try {
            return read.call();
        } finally {
            running.release();
        }
    }

    static final class Busy extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Busy() {
            super("Another long read of the audit trail is running");
        }
    }
}
