package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public record Intent(
        String id,
        Instant time,
        String tenant,
        String key,
        WireFormat endpoint,
        String model,
        String provider,
        String upstream,
        @Nullable String dataClass,
        String configVersion,
        String requestHmac,
        List<GuardStep> steps,
        Decision decision,
        @Nullable Money reservation)
        implements AuditRecord {

    public Intent {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(decision, "decision");
        Digests.requireSha256("configVersion", configVersion);
        Digests.requireSha256("requestHmac", requestHmac);
        steps = List.copyOf(steps);
    }

    public boolean allowed() {
        return decision instanceof Decision.Allow;
    }
}
