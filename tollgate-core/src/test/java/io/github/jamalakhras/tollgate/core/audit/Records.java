package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Prices;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

final class Records {

    static final Instant TIME = Instant.parse("2026-11-02T14:03:10.512Z");
    static final String CONFIG_VERSION = "3f1c" + "0".repeat(56) + "9ab2";
    static final String REQUEST_HMAC = "a91b" + "1".repeat(60);
    static final String RESPONSE_HMAC = "55d0" + "2".repeat(60);
    static final String USER_HMAC = "c4d8" + "6".repeat(60);
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
                Prices.usd("0.1523"),
                null,
                null);
    }

    static Intent edit(Intent intent, Consumer<Draft> change) {
        Draft draft = new Draft(intent);
        change.accept(draft);
        return draft.build();
    }

    static Intent attributed(String id) {
        return edit(intent(id), draft -> draft.user = USER_HMAC);
    }

    static Intent redacted(String id) {
        return edit(intent(id), draft -> draft.redactions = new Redactions(Map.of("card", 1, "email", 2), SENT_HMAC));
    }

    static Intent monitored(String id) {
        return withSteps(intent(id), null);
    }

    static Intent monitoredAndRedacted(String id) {
        return withSteps(intent(id), new Redactions(Map.of("card", 1, "email", 2), SENT_HMAC));
    }

    private static Intent withSteps(Intent intent, @Nullable Redactions redactions) {
        Decision denial = Decision.deny(Refusal.POLICY_DENIED, "Confidential data stays on local models");
        return edit(intent, draft -> {
            draft.steps = List.of(
                    new GuardStep("request-size", Decision.ALLOW, Duration.ofNanos(2_100)),
                    new GuardStep("data-class", denial, Duration.ofNanos(3_400), true));
            draft.redactions = redactions;
        });
    }

    static Intent refused(String id) {
        Decision denial = Decision.deny(Refusal.POLICY_DENIED, "Confidential data stays on local models");
        return edit(intent(id), draft -> {
            draft.endpoint = WireFormat.OPENAI;
            draft.model = "gpt";
            draft.provider = "openai";
            draft.upstream = "gpt-5";
            draft.dataClass = null;
            draft.steps = List.of(new GuardStep("data-class", denial, Duration.ofNanos(1_800)));
            draft.decision = denial;
            draft.reservation = null;
        });
    }

    static final class Draft {

        String id;
        Instant time;
        String tenant;
        String key;
        WireFormat endpoint;
        String model;
        String provider;
        String upstream;

        @Nullable
        String dataClass;

        String configVersion;
        String requestHmac;
        List<GuardStep> steps;
        Decision decision;

        @Nullable
        Money reservation;

        @Nullable
        Redactions redactions;

        @Nullable
        String user;

        private Draft(Intent from) {
            id = from.id();
            time = from.time();
            tenant = from.tenant();
            key = from.key();
            endpoint = from.endpoint();
            model = from.model();
            provider = from.provider();
            upstream = from.upstream();
            dataClass = from.dataClass();
            configVersion = from.configVersion();
            requestHmac = from.requestHmac();
            steps = from.steps();
            decision = from.decision();
            reservation = from.reservation();
            redactions = from.redactions();
            user = from.user();
        }

        private Intent build() {
            return new Intent(
                    id,
                    time,
                    tenant,
                    key,
                    endpoint,
                    model,
                    provider,
                    upstream,
                    dataClass,
                    configVersion,
                    requestHmac,
                    steps,
                    decision,
                    reservation,
                    redactions,
                    user);
        }
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
