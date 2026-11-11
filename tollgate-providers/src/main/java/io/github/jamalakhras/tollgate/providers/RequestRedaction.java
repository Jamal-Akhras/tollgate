package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.redact.Redactor;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class RequestRedaction {

    private static final List<String> OPENAI_TEXT_FIELDS =
            List.of("content", "refusal", "reasoning_content", "reasoning");

    private final Redactor redactor;

    private RequestRedaction(Redactor redactor) {
        this.redactor = redactor;
    }

    static void apply(WireFormat format, ObjectNode body, Redactor redactor) {
        RequestRedaction redaction = new RequestRedaction(redactor);
        switch (format) {
            case OPENAI -> redaction.openAi(body);
            case ANTHROPIC -> redaction.anthropic(body);
        }
    }

    private void openAi(ObjectNode body) {
        for (JsonNode message : body.path("messages")) {
            if (!(message instanceof ObjectNode fields)) {
                continue;
            }
            for (String field : OPENAI_TEXT_FIELDS) {
                text(fields, field);
            }
            for (JsonNode part : fields.path("content")) {
                if (part instanceof ObjectNode content) {
                    text(content, "text");
                    text(content, "refusal");
                }
            }
            for (JsonNode call : fields.path("tool_calls")) {
                if (call.path("function") instanceof ObjectNode function) {
                    json(function, "arguments");
                }
                if (call.path("custom") instanceof ObjectNode custom) {
                    text(custom, "input");
                }
            }
        }
    }

    private void anthropic(ObjectNode body) {
        text(body, "system");
        blocks(body.path("system"));
        values(body.path("safeguards"));
        for (JsonNode message : body.path("messages")) {
            if (message instanceof ObjectNode fields) {
                text(fields, "content");
                blocks(fields.path("content"));
            }
        }
    }

    private void blocks(JsonNode blocks) {
        for (JsonNode node : blocks) {
            if (!(node instanceof ObjectNode block)) {
                continue;
            }
            switch (block.path("type").asString("")) {
                case "text" -> text(block, "text");
                case "tool_use" -> values(block.path("input"));
                case "tool_result" -> {
                    text(block, "content");
                    blocks(block.path("content"));
                }
                case "document" -> {
                    if (block.path("source") instanceof ObjectNode source) {
                        if (source.path("type").asString("").equals("text")) {
                            text(source, "data");
                        }
                        blocks(source.path("content"));
                    }
                }
                default -> {}
            }
        }
    }

    private void text(ObjectNode node, String field) {
        if (node.path(field).isString()) {
            node.put(field, redactor.redact(node.path(field).stringValue()));
        }
    }

    private void json(ObjectNode node, String field) {
        if (!node.path(field).isString()) {
            return;
        }
        String text = node.path(field).stringValue();
        JsonNode parsed;
        try {
            parsed = ChatRequest.JSON.readTree(text);
        } catch (JacksonException e) {
            node.put(field, redactor.redact(text));
            return;
        }
        if (parsed.isContainer()) {
            if (values(parsed)) {
                node.put(field, ChatRequest.JSON.writeValueAsString(parsed));
            }
        } else {
            node.put(field, redactor.redact(text));
        }
    }

    private boolean values(JsonNode node) {
        boolean changed = false;
        if (node instanceof ObjectNode object) {
            for (String name : new ArrayList<>(object.propertyNames())) {
                JsonNode value = object.path(name);
                if (value.isString()) {
                    String redacted = redactor.redact(value.stringValue());
                    changed |= !redacted.equals(value.stringValue());
                    object.put(name, redacted);
                } else {
                    changed |= values(value);
                }
            }
        } else if (node instanceof ArrayNode array) {
            for (int i = 0; i < array.size(); i++) {
                JsonNode value = array.get(i);
                if (value.isString()) {
                    String redacted = redactor.redact(value.stringValue());
                    changed |= !redacted.equals(value.stringValue());
                    array.set(i, array.stringNode(redacted));
                } else {
                    changed |= values(value);
                }
            }
        }
        return changed;
    }
}
