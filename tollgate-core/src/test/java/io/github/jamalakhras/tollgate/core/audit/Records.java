package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Prices;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

final class Records {

    static final Instant TIME = Instant.parse("2026-11-02T14:03:10.512Z");
    static final String CONFIG_VERSION = "3f1c" + "0".repeat(56) + "9ab2";
    static final String REQUEST_HMAC = "a91b" + "1".repeat(60);
    static final String RESPONSE_HMAC = "55d0" + "2".repeat(60);
    static final String SENT_HMAC = "e7a3" + "5".repeat(60);

    private Records() {}

    static Intent intent(String id) {
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
                CONFIG_VERSION,
                REQUEST_HMAC,
                List.of(
                        new GuardStep("request-size", Decision.ALLOW, Duration.ofNanos(2_100)),
                        new GuardStep("data-class", Decision.ALLOW, Duration.ofNanos(3_400))),
                Decision.ALLOW,
                Prices.usd("0.1523"));
    }

    static Intent redacted(String id) {
        Intent intent = intent(id);
        return new Intent(
                intent.id(),
                intent.time(),
                intent.tenant(),
                intent.key(),
                intent.endpoint(),
                intent.model(),
                intent.provider(),
                intent.upstream(),
                intent.dataClass(),
                intent.configVersion(),
                intent.requestHmac(),
                intent.steps(),
                intent.decision(),
                intent.reservation(),
                new Redactions(Map.of("card", 1, "email", 2), SENT_HMAC));
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
                CONFIG_VERSION,
                REQUEST_HMAC,
                List.of(new GuardStep("data-class", denial, Duration.ofNanos(1_800))),
                denial,
                null);
    }

    static Outcome outcome(String id) {
        return new Outcome(
                id,
                TIME.plusMillis(1_528),
                Outcome.Result.RESPONDED,
                200,
                new Usage(812, 304, 0, 0),
                Prices.usd("0.006996"),
                Duration.ofMillis(1_527),
                RESPONSE_HMAC);
    }

    static Outcome unreachable(String id) {
        return new Outcome(
                id,
                TIME.plusSeconds(1),
                Outcome.Result.UNREACHABLE,
                null,
                null,
                Prices.usd("0"),
                Duration.ofMillis(40),
                null);
    }
}
