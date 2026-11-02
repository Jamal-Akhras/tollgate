package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class StreamRewriter {

    private static final String DATA = "data:";
    private static final String DONE = "[DONE]";

    private final WireFormat format;
    private final String model;
    private final boolean relayUsage;
    private @Nullable Usage usage;

    public StreamRewriter(WireFormat format, String model, boolean relayUsage) {
        this.format = format;
        this.model = model;
        this.relayUsage = relayUsage;
    }

    public Optional<byte[]> rewrite(List<String> event) {
        StringBuilder out = new StringBuilder();
        boolean relay = true;
        for (String line : event) {
            if (!line.startsWith(DATA)) {
                out.append(line).append('\n');
                continue;
            }
            String payload = line.substring(DATA.length()).strip();
            if (payload.equals(DONE)) {
                out.append(line).append('\n');
                continue;
            }
            ObjectNode data = object(payload);
            if (data.has("error")) {
                throw new InvalidProviderResponseException("The provider reported an error during the stream");
            }
            relay &= read(data);
            if (data.has("model")) {
                data.put("model", model);
            }
            out.append(DATA)
                    .append(' ')
                    .append(ChatRequest.JSON.writeValueAsString(data))
                    .append('\n');
        }
        return relay ? Optional.of(out.append('\n').toString().getBytes(StandardCharsets.UTF_8)) : Optional.empty();
    }

    public Optional<Usage> usage() {
        return Optional.ofNullable(usage);
    }

    private boolean read(ObjectNode data) {
        return switch (format) {
            case OPENAI -> {
                JsonNode reported = data.path("usage");
                if (!reported.isObject()) {
                    yield true;
                }
                UsageReader.openAi(reported).ifPresent(found -> usage = found);
                JsonNode choices = data.path("choices");
                yield relayUsage || !choices.isArray() || !choices.isEmpty();
            }
            case ANTHROPIC -> true;
        };
    }

    private static ObjectNode object(String payload) {
        try {
            if (ChatRequest.JSON.readTree(payload) instanceof ObjectNode object) {
                return object;
            }
        } catch (JacksonException e) {
            throw new InvalidProviderResponseException("The provider sent an event that is not JSON");
        }
        throw new InvalidProviderResponseException("The provider sent an event that is not a JSON object");
    }
}
