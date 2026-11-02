package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.RequestFeatures;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

final class RequestReview {

    private static final Set<String> OPENAI_FIELDS = Set.of(
            "model",
            "messages",
            "max_tokens",
            "max_completion_tokens",
            "n",
            "temperature",
            "top_p",
            "stop",
            "seed",
            "presence_penalty",
            "frequency_penalty",
            "logit_bias",
            "logprobs",
            "top_logprobs",
            "tools",
            "tool_choice",
            "parallel_tool_calls",
            "response_format",
            "reasoning_effort",
            "verbosity",
            "user",
            "safety_identifier",
            "prompt_cache_key",
            "store",
            "stream");
    private static final Set<String> ANTHROPIC_FIELDS = Set.of(
            "model",
            "messages",
            "system",
            "max_tokens",
            "temperature",
            "top_p",
            "top_k",
            "stop_sequences",
            "tools",
            "tool_choice",
            "thinking",
            "metadata",
            "stream");
    private static final Map<String, String> REFUSED_FIELDS = Map.of(
            "mcp_servers", "it makes the provider connect to other servers",
            "container", "it runs code in a provider sandbox",
            "web_search_options", "it sends searches to a search engine",
            "store", "it keeps the conversation at the provider",
            "metadata", "it keeps the conversation at the provider",
            "service_tier", "it changes the price Tollgate budgets for",
            "modalities", "audio is priced differently from text",
            "audio", "audio is priced differently from text");
    private static final Set<String> OPENAI_TOOLS = Set.of("function", "custom");
    private static final List<String> ANTHROPIC_CLIENT_TOOLS =
            List.of("custom", "bash_", "text_editor_", "computer_", "memory_");
    private static final List<String> REFUSED_BETAS =
            List.of("mcp-client-", "code-execution-", "web-fetch-", "files-api-", "context-1m-");

    private final List<String> refused = new ArrayList<>();
    private final List<String> unreviewed = new ArrayList<>();
    private final Set<Feature> used = EnumSet.noneOf(Feature.class);

    private RequestReview() {}

    static RequestFeatures of(WireFormat format, ObjectNode body, @Nullable String betas) {
        RequestReview review = new RequestReview();
        switch (format) {
            case OPENAI -> review.openAi(body);
            case ANTHROPIC -> review.anthropic(body, betas);
        }
        return new RequestFeatures(review.refused, review.unreviewed, review.used);
    }

    private void openAi(ObjectNode body) {
        fields(body, OPENAI_FIELDS);
        JsonNode store = body.path("store");
        if (!store.isMissingNode() && !(store.isBoolean() && !store.booleanValue())) {
            refuseField("store");
        }
        for (JsonNode message : body.path("messages")) {
            JsonNode content = message.path("content");
            if (content.isArray()) {
                content.forEach(this::openAiPart);
            }
        }
        for (JsonNode tool : body.path("tools")) {
            String type = tool.path("type").asString("");
            if (!OPENAI_TOOLS.contains(type)) {
                refuseTool(type);
            }
        }
    }

    private void openAiPart(JsonNode part) {
        String type = part.path("type").asString("");
        switch (type) {
            case "text", "refusal" -> {}
            case "image_url" -> url(part.path("image_url").path("url").asString(""));
            case "file" -> {
                if (part.path("file").has("file_id")) {
                    used.add(Feature.PROVIDER_FILES);
                }
            }
            default -> refused.add("The content type '" + type + "' is not allowed");
        }
    }

    private void url(String url) {
        if (url.startsWith("data:")) {
            return;
        }
        if (url.startsWith("https://") || url.startsWith("http://")) {
            used.add(Feature.REMOTE_IMAGES);
            return;
        }
        refused.add("Images must be inline data or an http or https URL");
    }

    private void anthropic(ObjectNode body, @Nullable String betas) {
        fields(body, ANTHROPIC_FIELDS);
        JsonNode system = body.path("system");
        if (system.isArray()) {
            system.forEach(this::anthropicBlock);
        }
        for (JsonNode message : body.path("messages")) {
            blocks(message.path("content"));
        }
        for (JsonNode tool : body.path("tools")) {
            String type = tool.path("type").asString("custom");
            if (ANTHROPIC_CLIENT_TOOLS.stream().noneMatch(type::startsWith)) {
                refuseTool(type);
            }
        }
        if (betas != null) {
            for (String beta : betas.split(",", -1)) {
                beta(beta.strip());
            }
        }
    }

    private void blocks(JsonNode content) {
        if (content.isArray()) {
            content.forEach(this::anthropicBlock);
        }
    }

    private void anthropicBlock(JsonNode block) {
        String type = block.path("type").asString("");
        switch (type) {
            case "text", "thinking", "redacted_thinking", "tool_use" -> {}
            case "tool_result" -> blocks(block.path("content"));
            case "image" -> source(block.path("source"), Feature.REMOTE_IMAGES);
            case "document" -> source(block.path("source"), Feature.REMOTE_DOCUMENTS);
            default -> refused.add("The content type '" + type + "' is not allowed");
        }
    }

    private void source(JsonNode source, Feature remote) {
        String type = source.path("type").asString("");
        switch (type) {
            case "base64", "text" -> {}
            case "content" -> blocks(source.path("content"));
            case "url" -> used.add(remote);
            case "file" -> used.add(Feature.PROVIDER_FILES);
            default -> refused.add("The source type '" + type + "' is not allowed");
        }
    }

    private void beta(String beta) {
        if (beta.isEmpty()) {
            return;
        }
        if (REFUSED_BETAS.stream().anyMatch(beta::startsWith)) {
            refused.add("The anthropic-beta '" + beta + "' is not allowed: it switches on a provider ability"
                    + " Tollgate refuses");
        } else {
            unreviewed.add("The anthropic-beta '" + beta + "' is not one Tollgate has reviewed");
        }
    }

    private void fields(ObjectNode body, Set<String> allowed) {
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            String name = field.getKey();
            if (allowed.contains(name)) {
                continue;
            }
            if (REFUSED_FIELDS.containsKey(name)) {
                refuseField(name);
            } else {
                unreviewed.add("The field '" + name + "' is not one Tollgate has reviewed");
            }
        }
    }

    private void refuseField(String name) {
        refused.add("The field '" + name + "' is not allowed: " + REFUSED_FIELDS.get(name));
    }

    private void refuseTool(String type) {
        refused.add("The tool type '" + type + "' is not allowed: it runs on the provider, outside Tollgate's view");
    }
}
