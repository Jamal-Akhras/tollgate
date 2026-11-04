package io.github.jamalakhras.tollgate.server.audit;

import io.github.jamalakhras.tollgate.core.audit.AuditEntry;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.audit.Redactions;
import io.github.jamalakhras.tollgate.core.audit.Seal;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.GuardStep;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.core.redact.Detector;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class AuditJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final int VERSION = 1;
    private static final int REDACTED_VERSION = 2;

    private AuditJson() {}

    public static String write(AuditEntry entry) {
        boolean redacted = entry instanceof Intent intent && intent.redactions() != null;
        ObjectNode line = JSON.createObjectNode().put("v", redacted ? REDACTED_VERSION : VERSION);
        switch (entry) {
            case Intent intent -> writeIntent(line, intent);
            case Outcome outcome -> writeOutcome(line, outcome);
            case Seal seal -> writeSeal(line, seal);
        }
        return JSON.writeValueAsString(line);
    }

    public static AuditEntry read(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Not valid JSON: " + e.getOriginalMessage(), e);
        }
        Fields fields = Fields.of("the line", root);
        long version = fields.whole("v");
        String type = fields.text("type");
        if (version == REDACTED_VERSION && type.equals("intent")) {
            return readIntent(fields, true);
        }
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported audit record version " + version);
        }
        return switch (type) {
            case "intent" -> readIntent(fields, false);
            case "outcome" -> readOutcome(fields);
            case "seal" -> readSeal(fields);
            default -> throw new IllegalArgumentException("Unknown line type '" + type + "'");
        };
    }

    private static void writeIntent(ObjectNode line, Intent intent) {
        line.put("type", "intent")
                .put("id", intent.id())
                .put("time", intent.time().toString())
                .put("tenant", intent.tenant())
                .put("key", intent.key())
                .put("endpoint", intent.endpoint().configName())
                .put("model", intent.model())
                .put("provider", intent.provider())
                .put("upstream", intent.upstream())
                .put("dataClass", intent.dataClass())
                .put("configVersion", intent.configVersion())
                .put("requestHmac", intent.requestHmac());
        ArrayNode steps = line.putArray("steps");
        for (GuardStep step : intent.steps()) {
            ObjectNode written = steps.addObject().put("guard", step.guard());
            written.set("decision", decision(step.decision()));
            written.put("nanos", step.duration().toNanos());
        }
        line.set("decision", decision(intent.decision()));
        Money reservation = intent.reservation();
        line.set("reservation", reservation == null ? line.nullNode() : money(reservation));
        Redactions redactions = intent.redactions();
        if (redactions != null) {
            ObjectNode written = line.putObject("redactions");
            ObjectNode values = written.putObject("values");
            redactions.values().forEach((detector, count) -> values.put(detector.configName(), count));
            written.put("sentHmac", redactions.sentHmac());
        }
    }

    private static void writeOutcome(ObjectNode line, Outcome outcome) {
        line.put("type", "outcome")
                .put("id", outcome.id())
                .put("time", outcome.time().toString())
                .put("result", outcome.result().code())
                .put("status", outcome.status());
        Usage usage = outcome.usage();
        if (usage == null) {
            line.putNull("usage");
        } else {
            line.putObject("usage")
                    .put("input", usage.inputTokens())
                    .put("output", usage.outputTokens())
                    .put("cacheRead", usage.cacheReadTokens())
                    .put("cacheWrite", usage.cacheWriteTokens());
        }
        line.set("cost", money(outcome.cost()));
        line.put("nanos", outcome.duration().toNanos()).put("responseHmac", outcome.responseHmac());
    }

    private static void writeSeal(ObjectNode line, Seal seal) {
        line.put("type", "seal")
                .put("batch", seal.batch())
                .put("records", seal.records())
                .put("time", seal.time().toString())
                .put("previous", seal.previous())
                .put("root", seal.root())
                .put("key", seal.key())
                .put("hmacKey", seal.hmacKey())
                .put("signature", seal.signature());
    }

    private static ObjectNode decision(Decision decision) {
        ObjectNode written = JSON.createObjectNode();
        switch (decision) {
            case Decision.Allow allow -> written.put("verdict", "allow");
            case Decision.Deny deny ->
                written.put("verdict", "deny")
                        .put("refusal", deny.refusal().code())
                        .put("message", deny.message());
        }
        return written;
    }

    private static ObjectNode money(Money money) {
        return JSON.createObjectNode()
                .put("amount", money.toBigDecimal().toPlainString())
                .put("currency", money.currency().getCurrencyCode());
    }

    private static Intent readIntent(Fields fields, boolean redacted) {
        List<String> names = new ArrayList<>(List.of(
                "v",
                "type",
                "id",
                "time",
                "tenant",
                "key",
                "endpoint",
                "model",
                "provider",
                "upstream",
                "dataClass",
                "configVersion",
                "requestHmac",
                "steps",
                "decision",
                "reservation"));
        if (redacted) {
            names.add("redactions");
        }
        fields.requireExactly(names.toArray(String[]::new));
        List<GuardStep> steps = new ArrayList<>();
        for (Fields step : fields.list("steps")) {
            step.requireExactly("guard", "decision", "nanos");
            steps.add(new GuardStep(
                    step.text("guard"), readDecision(step.child("decision")), Duration.ofNanos(step.whole("nanos"))));
        }
        return fields.build(() -> new Intent(
                fields.text("id"),
                fields.instant("time"),
                fields.text("tenant"),
                fields.text("key"),
                endpoint(fields.text("endpoint")),
                fields.text("model"),
                fields.text("provider"),
                fields.text("upstream"),
                fields.optionalText("dataClass"),
                fields.text("configVersion"),
                fields.text("requestHmac"),
                steps,
                readDecision(fields.child("decision")),
                fields.optionalChild("reservation", AuditJson::readMoney),
                redacted ? readRedactions(fields.child("redactions")) : null));
    }

    private static Redactions readRedactions(Fields fields) {
        fields.requireExactly("values", "sentHmac");
        Fields values = fields.child("values");
        Map<Detector, Integer> counts = new EnumMap<>(Detector.class);
        for (String name : values.node().propertyNames()) {
            counts.put(
                    values.build(() -> Detector.fromConfigName(name)),
                    values.build(() -> Math.toIntExact(values.whole(name))));
        }
        return fields.build(() -> new Redactions(counts, fields.text("sentHmac")));
    }

    private static Outcome readOutcome(Fields fields) {
        fields.requireExactly("v", "type", "id", "time", "result", "status", "usage", "cost", "nanos", "responseHmac");
        Long status = fields.optionalWhole("status");
        return fields.build(() -> new Outcome(
                fields.text("id"),
                fields.instant("time"),
                Outcome.Result.fromCode(fields.text("result")),
                status == null ? null : Math.toIntExact(status),
                fields.optionalChild("usage", AuditJson::readUsage),
                readMoney(fields.child("cost")),
                Duration.ofNanos(fields.whole("nanos")),
                fields.optionalText("responseHmac")));
    }

    private static Seal readSeal(Fields fields) {
        fields.requireExactly(
                "v", "type", "batch", "records", "time", "previous", "root", "key", "hmacKey", "signature");
        long records = fields.whole("records");
        return fields.build(() -> new Seal(
                fields.whole("batch"),
                Math.toIntExact(records),
                fields.instant("time"),
                fields.text("previous"),
                fields.text("root"),
                fields.text("key"),
                fields.text("hmacKey"),
                fields.text("signature")));
    }

    private static Decision readDecision(Fields fields) {
        String verdict = fields.text("verdict");
        return switch (verdict) {
            case "allow" -> {
                fields.requireExactly("verdict");
                yield Decision.ALLOW;
            }
            case "deny" -> {
                fields.requireExactly("verdict", "refusal", "message");
                yield fields.build(
                        () -> Decision.deny(Refusal.fromCode(fields.text("refusal")), fields.text("message")));
            }
            default -> throw new IllegalArgumentException("Unknown verdict '" + verdict + "'");
        };
    }

    private static Usage readUsage(Fields fields) {
        fields.requireExactly("input", "output", "cacheRead", "cacheWrite");
        return fields.build(() -> new Usage(
                fields.whole("input"), fields.whole("output"), fields.whole("cacheRead"), fields.whole("cacheWrite")));
    }

    private static Money readMoney(Fields fields) {
        fields.requireExactly("amount", "currency");
        String amount = fields.text("amount");
        String code = fields.text("currency");
        Currency currency;
        try {
            currency = Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'currency' is not a currency code: " + code, e);
        }
        try {
            return Money.of(new BigDecimal(amount), currency);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("'amount' must be a number with at most 9 decimal places: " + amount, e);
        }
    }

    private static WireFormat endpoint(String name) {
        return Arrays.stream(WireFormat.values())
                .filter(format -> format.configName().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown endpoint '" + name + "'"));
    }

    private record Fields(String where, ObjectNode node) {

        static Fields of(String where, JsonNode node) {
            if (!(node instanceof ObjectNode object)) {
                throw new IllegalArgumentException(where + " must be a JSON object");
            }
            return new Fields(where, object);
        }

        void requireExactly(String... names) {
            Set<String> expected = Set.of(names);
            Set<String> present = new HashSet<>(node.propertyNames());
            for (String name : present) {
                if (!expected.contains(name)) {
                    throw new IllegalArgumentException("'" + name + "' is not part of " + where);
                }
            }
            for (String name : names) {
                if (!present.contains(name)) {
                    throw new IllegalArgumentException("'" + name + "' is missing from " + where);
                }
            }
        }

        String text(String name) {
            JsonNode value = node.path(name);
            if (!value.isString()) {
                throw new IllegalArgumentException("'" + name + "' must be a string");
            }
            return value.stringValue();
        }

        @Nullable
        String optionalText(String name) {
            return node.path(name).isNull() ? null : text(name);
        }

        long whole(String name) {
            JsonNode value = node.path(name);
            if (!value.isIntegralNumber() || !value.canConvertToLong()) {
                throw new IllegalArgumentException("'" + name + "' must be a whole number");
            }
            return value.longValue();
        }

        @Nullable
        Long optionalWhole(String name) {
            return node.path(name).isNull() ? null : whole(name);
        }

        Instant instant(String name) {
            String text = text(name);
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("'" + name + "' is not a time: " + text, e);
            }
        }

        Fields child(String name) {
            return Fields.of("'" + name + "'", node.path(name));
        }

        List<Fields> list(String name) {
            JsonNode value = node.path(name);
            if (!value.isArray()) {
                throw new IllegalArgumentException("'" + name + "' must be a list");
            }
            List<Fields> items = new ArrayList<>();
            for (JsonNode item : value) {
                items.add(Fields.of("an item of '" + name + "'", item));
            }
            return items;
        }

        <T> @Nullable T optionalChild(String name, Function<Fields, T> reader) {
            return node.path(name).isNull() ? null : reader.apply(child(name));
        }

        <T> T build(Supplier<T> constructor) {
            try {
                return constructor.get();
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new IllegalArgumentException(String.valueOf(e.getMessage()), e);
            }
        }
    }
}
