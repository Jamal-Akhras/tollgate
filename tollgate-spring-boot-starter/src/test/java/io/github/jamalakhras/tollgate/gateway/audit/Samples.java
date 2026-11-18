package io.github.jamalakhras.tollgate.gateway.audit;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Redactions;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

final class Samples {

    static final AuditKeys.NewKeys NEW_KEYS = AuditKeys.generate(new SecureRandom());
    static final AuditKeys KEYS = AuditKeys.of(NEW_KEYS.publicKey(), NEW_KEYS.signingKey(), NEW_KEYS.hmacKey());
    static final Instant TIME = Instant.parse("2026-11-02T14:03:10.512Z");

    private static final String HASH = "3f1c" + "0".repeat(60);
    static final String USER = "a91d" + "7".repeat(60);

    private Samples() {}

    private static final List<GuardStep> STEPS =
            List.of(new GuardStep("request-size", Decision.ALLOW, Duration.ofNanos(2_100)));
    private static final Redactions REDACTIONS = new Redactions(Map.of("email", 2, "card", 1), "e7a3" + "5".repeat(60));

    static Intent intent(String id) {
        return intent(id, STEPS, null, null);
    }

    static Intent redacted(String id) {
        return intent(id, STEPS, REDACTIONS, null);
    }

    static Intent monitored(String id) {
        Decision denial = Decision.deny(Refusal.POLICY_DENIED, "Confidential data stays on local models");
        return intent(
                id,
                List.of(
                        new GuardStep("request-size", Decision.ALLOW, Duration.ofNanos(2_100)),
                        new GuardStep("data-class", denial, Duration.ofNanos(3_400), true)),
                REDACTIONS,
                null);
    }

    static Intent attributed(String id) {
        return intent(id, STEPS, null, USER);
    }

    static Intent intent(String id, List<GuardStep> steps, @Nullable Redactions redactions, @Nullable String user) {
        return new Intent(
                id,
                TIME,
                "support-bot",
                "k7Qm2xPa4bZr",
                WireFormat.ANTHROPIC,
                "claude-sonnet",
                "anthropic",
                "claude-sonnet-4-5",
                "internal",
                HASH,
                HASH,
                steps,
                Decision.ALLOW,
                usd("0.1523"),
                redactions,
                user);
    }

    static Intent refused(String id) {
        Decision denial = Decision.deny(Refusal.POLICY_DENIED, "Confidential data stays on local models");
        return new Intent(
                id,
                TIME,
                "support-bot",
                "k7Qm2xPa4bZr",
                WireFormat.OPENAI,
                "gpt",
                "openai",
                "gpt-5",
                null,
                HASH,
                HASH,
                List.of(new GuardStep("data-class", denial, Duration.ofNanos(1_800))),
                denial,
                null,
                null,
                null);
    }

    static Outcome outcome(String id) {
        return new Outcome(
                id,
                TIME.plusMillis(1_528),
                Outcome.Result.RESPONDED,
                200,
                new Usage(812, 304, 0, 0),
                usd("0.006996"),
                Duration.ofMillis(1_527),
                HASH);
    }

    static Outcome unreachable(String id) {
        return new Outcome(
                id, TIME.plusSeconds(1), Outcome.Result.UNREACHABLE, null, null, usd("0"), Duration.ofMillis(40), null);
    }

    private static Money usd(String amount) {
        return Money.of(new BigDecimal(amount), Currency.getInstance("USD"));
    }
}
