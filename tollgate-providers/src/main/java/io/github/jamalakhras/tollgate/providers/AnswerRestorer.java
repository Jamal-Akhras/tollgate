package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.redact.Placeholders;
import io.github.jamalakhras.tollgate.core.redact.StreamedText;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class AnswerRestorer {

    private static final String ARGUMENTS = "arguments";
    private static final String INPUT = "input";
    private static final String TEXT = "text";
    private static final String PARTIAL_JSON = "partial_json";

    private final Placeholders placeholders;
    private final boolean streaming;
    private final Map<Channel, StreamedText> held = new LinkedHashMap<>();
    private @Nullable ObjectNode lastChunk;

    AnswerRestorer(Placeholders placeholders, boolean streaming) {
        this.placeholders = placeholders;
        this.streaming = streaming;
    }

    static void openAi(ObjectNode response, Placeholders placeholders) {
        AnswerRestorer whole = new AnswerRestorer(placeholders, false);
        for (JsonNode node : response.path("choices")) {
            if (node instanceof ObjectNode choice && choice.path("message") instanceof ObjectNode message) {
                whole.message(message, choice.path("index").asInt(0));
            }
        }
    }

    static void anthropic(ObjectNode response, Placeholders placeholders) {
        AnswerRestorer whole = new AnswerRestorer(placeholders, false);
        for (JsonNode block : response.path("content")) {
            if (block instanceof ObjectNode content) {
                whole.block(content, -1);
            }
        }
    }

    void openAiChunk(ObjectNode chunk) {
        lastChunk = chunk;
        for (JsonNode node : chunk.path("choices")) {
            if (!(node instanceof ObjectNode choice)) {
                continue;
            }
            int index = choice.path("index").asInt(0);
            if (choice.path("delta") instanceof ObjectNode delta) {
                message(delta, index);
                if (!choice.path("finish_reason").isNull()
                        && !choice.path("finish_reason").isMissingNode()) {
                    release(index).forEach((channel, rest) -> append(delta, channel, rest));
                }
            }
        }
    }

    List<ObjectNode> openAiEnd() {
        List<ObjectNode> chunks = new ArrayList<>();
        ObjectNode template = lastChunk;
        if (template == null) {
            return chunks;
        }
        for (int index : heldChoices()) {
            Map<Channel, String> released = release(index);
            if (released.isEmpty()) {
                continue;
            }
            ObjectNode chunk = template.objectNode();
            for (String field : List.of("id", "object", "created", "model")) {
                if (template.has(field)) {
                    chunk.set(field, template.get(field));
                }
            }
            ObjectNode choice = chunk.putArray("choices").addObject().put("index", index);
            ObjectNode delta = choice.putObject("delta");
            released.forEach((channel, rest) -> append(delta, channel, rest));
            choice.putNull("finish_reason");
            chunks.add(chunk);
        }
        return chunks;
    }

    List<ObjectNode> anthropicEvent(ObjectNode event) {
        int index = event.path("index").asInt(-1);
        switch (event.path("type").asString("")) {
            case "content_block_start" -> {
                if (event.path("content_block") instanceof ObjectNode block) {
                    block(block, index);
                }
            }
            case "content_block_delta" -> {
                if (event.path("delta") instanceof ObjectNode delta) {
                    switch (delta.path("type").asString("")) {
                        case "text_delta" -> restore(delta, TEXT, new Channel(index, TEXT, -1, false));
                        case "input_json_delta" ->
                            restore(delta, PARTIAL_JSON, new Channel(index, PARTIAL_JSON, -1, true));
                        default -> {}
                    }
                }
            }
            case "content_block_stop" -> {
                return anthropicDeltas(release(index));
            }
            default -> {}
        }
        return List.of();
    }

    List<ObjectNode> anthropicEnd() {
        return anthropicDeltas(releaseAll());
    }

    private void message(ObjectNode message, int choice) {
        for (String field : new ArrayList<>(message.propertyNames())) {
            if (!field.equals("role") && message.path(field).isString()) {
                restore(message, field, new Channel(choice, field, -1, false));
            }
        }
        int position = 0;
        for (JsonNode node : message.path("tool_calls")) {
            if (node instanceof ObjectNode call) {
                int tool = call.path("index").asInt(position);
                if (call.path("function") instanceof ObjectNode function) {
                    restore(function, ARGUMENTS, new Channel(choice, ARGUMENTS, tool, true));
                }
                if (call.path("custom") instanceof ObjectNode custom) {
                    restore(custom, INPUT, new Channel(choice, INPUT, tool, false));
                }
            }
            position++;
        }
    }

    private void block(ObjectNode block, int index) {
        switch (block.path("type").asString("")) {
            case TEXT -> restore(block, TEXT, new Channel(index, TEXT, -1, false));
            case "tool_use" -> values(block.path(INPUT));
            default -> {}
        }
    }

    private void restore(ObjectNode node, String field, Channel channel) {
        if (!node.path(field).isString()) {
            return;
        }
        String text = node.path(field).stringValue();
        if (streaming) {
            node.put(
                    field,
                    held.computeIfAbsent(channel, c -> placeholders.streamed(c.encoded()))
                            .next(text));
        } else {
            node.put(field, channel.encoded() ? placeholders.restoreEncoded(text) : placeholders.restore(text));
        }
    }

    private void values(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode value = object.path(name);
                if (value.isString()) {
                    object.put(name, placeholders.restore(value.stringValue()));
                } else {
                    values(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isString()) {
                    array.set(i, array.stringNode(placeholders.restore(value.stringValue())));
                } else {
                    values(value);
                }
            }
        }
    }

    private Map<Channel, String> releaseAll() {
        Map<Channel, String> released = new LinkedHashMap<>();
        for (int at : held.keySet().stream().map(Channel::at).distinct().toList()) {
            released.putAll(release(at));
        }
        return released;
    }

    private Map<Channel, String> release(int at) {
        Map<Channel, String> released = new LinkedHashMap<>();
        Iterator<Map.Entry<Channel, StreamedText>> channels = held.entrySet().iterator();
        while (channels.hasNext()) {
            Map.Entry<Channel, StreamedText> channel = channels.next();
            if (channel.getKey().at() == at) {
                String rest = channel.getValue().flush();
                if (!rest.isEmpty()) {
                    released.put(channel.getKey(), rest);
                }
                channels.remove();
            }
        }
        return released;
    }

    private List<Integer> heldChoices() {
        return held.keySet().stream().map(Channel::at).distinct().toList();
    }

    private static void append(ObjectNode delta, Channel channel, String rest) {
        if (channel.tool() < 0) {
            delta.put(channel.field(), delta.path(channel.field()).asString("") + rest);
            return;
        }
        ArrayNode calls =
                delta.path("tool_calls") instanceof ArrayNode existing ? existing : delta.putArray("tool_calls");
        ObjectNode call = null;
        for (JsonNode node : calls) {
            if (node instanceof ObjectNode candidate && candidate.path("index").asInt(-1) == channel.tool()) {
                call = candidate;
            }
        }
        if (call == null) {
            call = calls.addObject().put("index", channel.tool());
        }
        String holder = channel.field().equals(ARGUMENTS) ? "function" : "custom";
        ObjectNode target = call.path(holder) instanceof ObjectNode found ? found : call.putObject(holder);
        target.put(channel.field(), target.path(channel.field()).asString("") + rest);
    }

    private static List<ObjectNode> anthropicDeltas(Map<Channel, String> released) {
        List<ObjectNode> events = new ArrayList<>();
        released.forEach((channel, rest) -> {
            ObjectNode event = ChatRequest.JSON.createObjectNode().put("type", "content_block_delta");
            event.put("index", channel.at());
            ObjectNode delta = event.putObject("delta");
            if (channel.field().equals(PARTIAL_JSON)) {
                delta.put("type", "input_json_delta").put(PARTIAL_JSON, rest);
            } else {
                delta.put("type", "text_delta").put(TEXT, rest);
            }
            events.add(event);
        });
        return events;
    }

    private record Channel(int at, String field, int tool, boolean encoded) {}
}
