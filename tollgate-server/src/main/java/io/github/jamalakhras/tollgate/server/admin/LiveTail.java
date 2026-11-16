package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.audit.AuditDirectory;
import io.github.jamalakhras.tollgate.server.admin.LiveViews.Live;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LiveTail implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LiveTail.class);
    private static final int PASS_BYTES = 4 * 1024 * 1024;
    private static final Duration WARNING_GAP = Duration.ofMinutes(1);

    private final Supplier<Optional<Path>> directory;
    private final Clock clock;
    private final int passBytes;
    private final LiveWindow window;
    private volatile Live current;
    private @Nullable ScheduledExecutorService executor;
    private @Nullable Path followed;
    private long offset;
    private boolean first = true;
    private @Nullable Instant lastWarning;

    LiveTail(Supplier<Optional<Path>> directory, Clock clock) {
        this(directory, clock, PASS_BYTES);
    }

    LiveTail(Supplier<Optional<Path>> directory, Clock clock, int passBytes) {
        this.directory = directory;
        this.clock = clock;
        this.passBytes = passBytes;
        this.window = new LiveWindow(clock.instant());
        this.current = window.snapshot(clock.instant());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void start() {
        ScheduledExecutorService running = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "tollgate-console");
            thread.setDaemon(true);
            return thread;
        });
        running.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        executor = running;
    }

    @Override
    public void close() {
        ScheduledExecutorService running = executor;
        if (running != null) {
            running.shutdownNow();
        }
    }

    Live current() {
        return current;
    }

    void tick() {
        try {
            follow();
        } catch (IOException | RuntimeException e) {
            warn(e);
        }
        current = window.snapshot(clock.instant());
    }

    private void follow() throws IOException {
        Optional<Path> trail = directory.get();
        if (trail.isEmpty()) {
            return;
        }
        List<Path> files = AuditDirectory.files(trail.get());
        if (files.isEmpty()) {
            first = false;
            return;
        }
        Path newest = files.getLast();
        if (!newest.equals(followed)) {
            followed = newest;
            offset = first ? Files.size(newest) : 0;
        }
        first = false;
        long size = Files.size(newest);
        boolean behind = size - offset > passBytes;
        offset = AuditDirectory.follow(newest, offset, passBytes, window::add);
        if (behind) {
            offset = size;
            window.skipped();
        }
    }

    private void warn(Exception e) {
        Instant now = clock.instant();
        Instant last = lastWarning;
        if (last == null || now.isAfter(last.plus(WARNING_GAP))) {
            lastWarning = now;
            log.warn("The console could not read the audit trail", e);
        }
    }
}
