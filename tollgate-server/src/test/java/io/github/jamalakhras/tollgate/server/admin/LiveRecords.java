package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Redactions;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class LiveRecords {

    static final String HASH = "a".repeat(64);

    private LiveRecords() {}

    static Intent intent(
            String id,
            Instant time,
            String tenant,
            List<GuardStep> steps,
            Decision decision,
            @Nullable Redactions redactions) {
        return new Intent(
                id,
                time,
                tenant,
                "key1",
                WireFormat.OPENAI,
                "qwen3",
                "ollama",
                "qwen3:8b",
                null,
                HASH,
                HASH,
                steps,
                decision,
                null,
                redactions,
                null);
    }

    static Outcome outcome(String id, Instant time, Outcome.Result result, long millis, Money cost) {
        return new Outcome(id, time, result, 200, new Usage(10, 5, 0, 0), cost, Duration.ofMillis(millis), null);
    }
}
