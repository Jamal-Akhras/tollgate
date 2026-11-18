package io.github.jamalakhras.tollgate.providers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public final class ProviderStream implements AutoCloseable {

    private final int status;
    private final @Nullable String retryAfter;
    private final Lines lines;
    private final Duration gap;

    ProviderStream(int status, @Nullable String retryAfter, Lines lines, Duration gap) {
        this.status = status;
        this.retryAfter = retryAfter;
        this.lines = lines;
        this.gap = gap;
    }

    public int status() {
        return status;
    }

    public ProviderResponse whole() throws InterruptedException {
        StringBuilder body = new StringBuilder();
        for (String line = lines.next(gap); line != null; line = lines.next(gap)) {
            body.append(line).append('\n');
        }
        return new ProviderResponse(status, body.toString().getBytes(StandardCharsets.UTF_8), null, retryAfter);
    }

    public @Nullable List<String> nextEvent(Duration remaining) throws InterruptedException {
        List<String> event = new ArrayList<>();
        while (true) {
            String line = lines.next(remaining.compareTo(gap) < 0 ? remaining : gap);
            if (line == null) {
                return event.isEmpty() ? null : event;
            }
            if (line.isEmpty()) {
                if (!event.isEmpty()) {
                    return event;
                }
            } else {
                event.add(line);
            }
        }
    }

    @Override
    public void close() {
        lines.cancel();
    }
}
