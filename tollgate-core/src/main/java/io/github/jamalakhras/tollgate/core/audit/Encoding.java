package io.github.jamalakhras.tollgate.core.audit;

import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

final class Encoding {

    static final int VERSION = 1;

    private static final int INTENT = 1;
    private static final int OUTCOME = 2;
    private static final int SEAL = 3;

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);

    private Encoding() {}

    static byte[] of(AuditEntry entry) {
        Encoding encoding = new Encoding();
        switch (entry) {
            case Intent intent -> encoding.intent(intent);
            case Outcome outcome -> encoding.outcome(outcome);
            case Seal seal -> encoding.seal(seal);
        }
        return encoding.bytes.toByteArray();
    }

    private void intent(Intent intent) {
        header(INTENT);
        text(intent.id());
        instant(intent.time());
        text(intent.tenant());
        text(intent.key());
        text(intent.endpoint().configName());
        text(intent.model());
        text(intent.provider());
        text(intent.upstream());
        optionalText(intent.dataClass());
        sha256(intent.configVersion());
        sha256(intent.requestHmac());
        u32(intent.steps().size());
        for (GuardStep step : intent.steps()) {
            text(step.guard());
            decision(step.decision());
            i64(step.duration().toNanos());
        }
        decision(intent.decision());
        Money reservation = intent.reservation();
        present(reservation != null);
        if (reservation != null) {
            money(reservation);
        }
    }

    private void outcome(Outcome outcome) {
        header(OUTCOME);
        text(outcome.id());
        instant(outcome.time());
        text(outcome.result().code());
        Integer status = outcome.status();
        present(status != null);
        if (status != null) {
            u32(status);
        }
        Usage usage = outcome.usage();
        present(usage != null);
        if (usage != null) {
            i64(usage.inputTokens());
            i64(usage.outputTokens());
            i64(usage.cacheReadTokens());
            i64(usage.cacheWriteTokens());
        }
        money(outcome.cost());
        i64(outcome.duration().toNanos());
        String responseHmac = outcome.responseHmac();
        present(responseHmac != null);
        if (responseHmac != null) {
            sha256(responseHmac);
        }
    }

    private void seal(Seal seal) {
        header(SEAL);
        i64(seal.batch());
        u32(seal.records());
        instant(seal.time());
        sha256(seal.previous());
        sha256(seal.root());
        text(seal.key());
        text(seal.hmacKey());
    }

    private void header(int type) {
        bytes.write(VERSION);
        bytes.write(type);
    }

    private void decision(Decision decision) {
        switch (decision) {
            case Decision.Allow allow -> bytes.write(0);
            case Decision.Deny deny -> {
                bytes.write(1);
                text(deny.refusal().code());
                text(deny.message());
            }
        }
    }

    private void money(Money money) {
        text(money.currency().getCurrencyCode());
        i64(money.nanos());
    }

    private void instant(Instant instant) {
        i64(instant.getEpochSecond());
        u32(instant.getNano());
    }

    private void optionalText(@Nullable String text) {
        present(text != null);
        if (text != null) {
            text(text);
        }
    }

    private void present(boolean present) {
        bytes.write(present ? 1 : 0);
    }

    private void text(String text) {
        byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
        u32(utf8.length);
        bytes.writeBytes(utf8);
    }

    private void sha256(String hex) {
        bytes.writeBytes(Digests.HEX.parseHex(hex));
    }

    private void i64(long value) {
        u32((int) (value >>> 32));
        u32((int) value);
    }

    private void u32(int value) {
        bytes.write(value >>> 24);
        bytes.write(value >>> 16);
        bytes.write(value >>> 8);
        bytes.write(value);
    }
}
