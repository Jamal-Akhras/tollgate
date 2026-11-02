package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.OutputTokens;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

public final class ChatRequest {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final String MAX_TOKENS = "max_tokens";
    private static final List<String> OPENAI_OUTPUT_FIELDS = List.of(MAX_TOKENS, "max_completion_tokens");

    private final ObjectNode body;
    private final String model;

    private ChatRequest(ObjectNode body, String model) {
        this.body = body;
        this.model = model;
    }

    public static ChatRequest parse(byte[] json) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new InvalidRequestException("The request body is not valid JSON: " + e.getOriginalMessage(), null);
        }
        if (!(root instanceof ObjectNode object)) {
            throw new InvalidRequestException("The request body must be a JSON object", null);
        }
        JsonNode model = object.path("model");
        if (!model.isString() || model.stringValue().isBlank()) {
            throw new InvalidRequestException("'model' is required and must be a string", "model");
        }
        return new ChatRequest(object, model.stringValue());
    }

    public String model() {
        return model;
    }

    public RequestFeatures review(WireFormat format, @Nullable String betas) {
        return RequestReview.of(format, body, betas);
    }

    public boolean isStreaming() {
        JsonNode stream = body.path("stream");
        boolean off = stream.isMissingNode() || stream.isNull() || (stream.isBoolean() && !stream.booleanValue());
        return !off;
    }

    public Forwarded forward(
            String upstreamModel, WireFormat format, @Nullable OutputTokens limit, long maxWebSearches) {
        Map<String, Long> outputFields = limitedOutputFields(format, limit);
        ObjectNode forwarded = body.objectNode().setAll(body).put("model", upstreamModel);
        outputFields.forEach(forwarded::put);
        long webSearches = format == WireFormat.ANTHROPIC ? limitWebSearches(forwarded, maxWebSearches) : 0;
        OptionalLong maxOutputTokens =
                outputFields.values().stream().mapToLong(Long::longValue).max();
        return new Forwarded(JSON.writeValueAsBytes(forwarded), maxOutputTokens, webSearches);
    }

    private long limitWebSearches(ObjectNode forwarded, long maxWebSearches) {
        JsonNode tools = body.path("tools");
        if (!tools.isArray()) {
            return 0;
        }
        long webSearches = 0;
        ArrayNode limited = forwarded.putArray("tools");
        for (JsonNode tool : tools) {
            JsonNode copy = tool.deepCopy();
            if (copy instanceof ObjectNode search && RequestReview.isWebSearch(search)) {
                JsonNode requested = search.path("max_uses");
                long uses = requested.isIntegralNumber() && requested.canConvertToLong() && requested.longValue() > 0
                        ? Math.min(requested.longValue(), maxWebSearches)
                        : maxWebSearches;
                search.put("max_uses", uses);
                webSearches = Math.addExact(webSearches, uses);
            }
            limited.add(copy);
        }
        return webSearches;
    }

    private Map<String, Long> limitedOutputFields(WireFormat format, @Nullable OutputTokens limit) {
        List<String> fields = format == WireFormat.ANTHROPIC ? List.of(MAX_TOKENS) : OPENAI_OUTPUT_FIELDS;
        Map<String, Long> limited = new LinkedHashMap<>();
        for (String field : fields) {
            JsonNode value = body.path(field);
            if (value.isMissingNode() || value.isNull()) {
                continue;
            }
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
                throw new InvalidRequestException("'" + field + "' must be a positive whole number", field);
            }
            limited.put(field, limit == null ? value.longValue() : limit.limit(value.longValue()));
        }
        if (limited.isEmpty() && limit != null) {
            limited.put(MAX_TOKENS, limit.defaultTokens());
        }
        return limited;
    }

    public static final class Forwarded {

        private final byte[] body;
        private final OptionalLong maxOutputTokens;
        private final long webSearches;

        private Forwarded(byte[] body, OptionalLong maxOutputTokens, long webSearches) {
            this.body = body;
            this.maxOutputTokens = maxOutputTokens;
            this.webSearches = webSearches;
        }

        public byte[] body() {
            return body.clone();
        }

        public int size() {
            return body.length;
        }

        public OptionalLong maxOutputTokens() {
            return maxOutputTokens;
        }

        public long webSearches() {
            return webSearches;
        }
    }
}
