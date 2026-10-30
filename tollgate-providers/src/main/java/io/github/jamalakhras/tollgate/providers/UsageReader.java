package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Usage;
import java.util.Optional;
import java.util.OptionalLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

public final class UsageReader {

    private UsageReader() {}

    public static Optional<Usage> read(WireFormat format, byte[] responseBody) {
        JsonNode usage;
        try {
            usage = ChatRequest.JSON.readTree(responseBody).path("usage");
        } catch (JacksonException e) {
            return Optional.empty();
        }
        return switch (format) {
            case OPENAI -> openAi(usage);
            case ANTHROPIC -> anthropic(usage);
        };
    }

    private static Optional<Usage> openAi(JsonNode usage) {
        OptionalLong prompt = count(usage.path("prompt_tokens"));
        OptionalLong completion = count(usage.path("completion_tokens"));
        OptionalLong cached = countOrZero(usage.path("prompt_tokens_details").path("cached_tokens"));
        if (prompt.isEmpty() || completion.isEmpty() || cached.isEmpty()) {
            return Optional.empty();
        }
        if (cached.getAsLong() > prompt.getAsLong()) {
            return Optional.empty();
        }
        return Optional.of(
                new Usage(prompt.getAsLong() - cached.getAsLong(), completion.getAsLong(), cached.getAsLong(), 0));
    }

    private static Optional<Usage> anthropic(JsonNode usage) {
        OptionalLong input = count(usage.path("input_tokens"));
        OptionalLong output = count(usage.path("output_tokens"));
        OptionalLong cacheRead = countOrZero(usage.path("cache_read_input_tokens"));
        OptionalLong cacheWrite = countOrZero(usage.path("cache_creation_input_tokens"));
        if (input.isEmpty() || output.isEmpty() || cacheRead.isEmpty() || cacheWrite.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
                new Usage(input.getAsLong(), output.getAsLong(), cacheRead.getAsLong(), cacheWrite.getAsLong()));
    }

    private static OptionalLong count(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(value.longValue());
    }

    private static OptionalLong countOrZero(JsonNode value) {
        return value.isMissingNode() || value.isNull() ? OptionalLong.of(0) : count(value);
    }
}
