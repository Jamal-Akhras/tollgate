package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Placeholders;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class ProviderResponse {

    private final int status;
    private final byte[] body;
    private final @Nullable String contentType;
    private final @Nullable String retryAfter;

    ProviderResponse(int status, byte[] body, @Nullable String contentType, @Nullable String retryAfter) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
        this.retryAfter = retryAfter;
    }

    public int status() {
        return status;
    }

    public byte[] body() {
        return body.clone();
    }

    public @Nullable String contentType() {
        return contentType;
    }

    public @Nullable String retryAfter() {
        return retryAfter;
    }

    public ProviderResponse withModel(String model) {
        ObjectNode object = object();
        if (object.has("model")) {
            object.put("model", model);
        }
        return new ProviderResponse(status, ChatRequest.JSON.writeValueAsBytes(object), contentType, retryAfter);
    }

    public ProviderResponse restored(WireFormat format, Placeholders placeholders) {
        if (placeholders.isEmpty()) {
            return this;
        }
        ObjectNode object = object();
        switch (format) {
            case OPENAI -> AnswerRestorer.openAi(object, placeholders);
            case ANTHROPIC -> AnswerRestorer.anthropic(object, placeholders);
        }
        return new ProviderResponse(status, ChatRequest.JSON.writeValueAsBytes(object), contentType, retryAfter);
    }

    public Optional<String> errorMessage() {
        try {
            JsonNode message = ChatRequest.JSON.readTree(body).path("error").path("message");
            return message.isString() && !message.stringValue().isBlank()
                    ? Optional.of(message.stringValue())
                    : Optional.empty();
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private ObjectNode object() {
        JsonNode root;
        try {
            root = ChatRequest.JSON.readTree(body);
        } catch (JacksonException e) {
            throw new InvalidProviderResponseException("The provider's response was not JSON");
        }
        if (!(root instanceof ObjectNode object)) {
            throw new InvalidProviderResponseException("The provider's response was not a JSON object");
        }
        return object;
    }
}
