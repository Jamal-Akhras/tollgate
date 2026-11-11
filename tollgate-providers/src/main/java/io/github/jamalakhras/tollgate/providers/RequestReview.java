package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.policy.Feature;
import io.github.jamalakhras.tollgate.core.policy.RefusedFields;
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
            "stream",
            "stream_options");
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
            "stream",
            "context_management",
            "output_config",
            "safeguards");
    private static final Set<String> OPENAI_TOOLS = Set.of("function", "custom");
    private static final List<String> ANTHROPIC_CLIENT_TOOLS =
            List.of("custom", "bash_", "text_editor_", "computer_", "memory_");
    private static final String WEB_SEARCH_TOOL = "web_search_";
    private static final List<String> REFUSED_BETAS =
            List.of("mcp-client-", "code-execution-", "web-fetch-", "files-api-", "context-1m-");
    private static final List<String> REVIEWED_BETAS = List.of(
            "advanced-tool-use-",
            "advisor-tool-",
            "afk-mode-",
            "claude-code-",
            "context-management-",
            "dangerous-tool-use-",
            "effort-",
            "extended-cache-ttl-",
            "fallback-credit-",
            "inline-tools-",
            "interleaved-thinking-",
            "mid-conversation-tool-changes-",
            "per-turn-control-",
            "redact-thinking-",
            "structured-outputs-",
            "thinking-display-updates-",
            "thinking-token-count-");

    private long inlineImages;
    private final List<String> refused = new ArrayList<>();
    private final List<String> unreviewed = new ArrayList<>();
    private final Set<Feature> used = EnumSet.noneOf(Feature.class);

    private final List<String> extraFields;

    private RequestReview(List<String> extraFields) {
        this.extraFields = extraFields;
    }

    static RequestFeatures of(WireFormat format, ObjectNode body, @Nullable String betas, List<String> extraFields) {
        RequestReview review = new RequestReview(extraFields);
        switch (format) {
            case OPENAI -> review.openAi(body);
            case ANTHROPIC -> review.anthropic(body, betas);
        }
        return new RequestFeatures(review.refused, review.unreviewed, review.used);
    }

    static long inlineImages(WireFormat format, ObjectNode body) {
        RequestReview review = new RequestReview(List.of());
        switch (format) {
            case OPENAI -> review.openAi(body);
            case ANTHROPIC -> review.anthropic(body, null);
        }
        return review.inlineImages;
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
            inlineImages++;
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
        body.path("tools").forEach(this::anthropicTool);
        if (betas != null) {
            for (String beta : betas.split(",", -1)) {
                beta(beta.strip());
            }
        }
    }

    private void anthropicTool(JsonNode tool) {
        String type = tool.path("type").asString("custom");
        if (isWebSearch(tool)) {
            used.add(Feature.WEB_SEARCH);
        } else if (ANTHROPIC_CLIENT_TOOLS.stream().noneMatch(type::startsWith)) {
            refuseTool(type);
        }
    }

    static boolean isWebSearch(JsonNode tool) {
        return tool.path("type").asString("").startsWith(WEB_SEARCH_TOOL);
    }

    private void blocks(JsonNode content) {
        if (content.isArray()) {
            content.forEach(this::anthropicBlock);
        }
    }

    private void anthropicBlock(JsonNode block) {
        String type = block.path("type").asString("");
        switch (type) {
            case "text", "thinking", "redacted_thinking", "tool_use", "tool_reference", "tool_removal" -> {}
            case "tool_result" -> blocks(block.path("content"));
            case "tool_addition" -> {
                JsonNode definition = block.path("tool").path("definition");
                if (!definition.isMissingNode()) {
                    anthropicTool(definition);
                }
            }
            case "image" -> {
                if (block.path("source").path("type").asString("").equals("base64")) {
                    inlineImages++;
                }
                source(block.path("source"), Feature.REMOTE_IMAGES);
            }
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
        } else if (REVIEWED_BETAS.stream().noneMatch(beta::startsWith)) {
            unreviewed.add("The anthropic-beta '" + beta + "' is not one Tollgate has reviewed");
        }
    }

    private void fields(ObjectNode body, Set<String> allowed) {
        for (Map.Entry<String, JsonNode> field : body.properties()) {
            String name = field.getKey();
            if (allowed.contains(name) || extraFields.contains(name)) {
                continue;
            }
            if (RefusedFields.reason(name).isPresent()) {
                refuseField(name);
            } else {
                unreviewed.add("The field '" + name + "' is not one Tollgate has reviewed");
            }
        }
    }

    private void refuseField(String name) {
        refused.add("The field '" + name + "' is not allowed: "
                + RefusedFields.reason(name).orElse(""));
    }

    private void refuseTool(String type) {
        refused.add("The tool type '" + type + "' is not allowed: it runs on the provider, outside Tollgate's view");
    }
}
