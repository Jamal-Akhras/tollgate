package io.github.jamalakhras.tollgate.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

class AgentClientsTest extends GatewayTestSupport {

    private static final Path CLIENTS = Path.of("src", "test", "resources", "clients");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String ANSWER = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
             "content":[{"type":"text","text":"Done"}],"stop_reason":"end_turn",
             "usage":{"input_tokens":10,"output_tokens":2}}""";

    @ParameterizedTest
    @ValueSource(strings = "claude-code")
    void acceptsEverythingTheClientWasRecordedSending(String client) throws Exception {
        JsonNode recording = JsonMapper.builder().build().readTree(Files.readString(CLIENTS.resolve(client + ".json")));

        for (Map.Entry<String, JsonNode> call : recording.properties()) {
            String path = call.getKey().substring(call.getKey().indexOf(' ') + 1);
            ObjectNode body = (ObjectNode) example(call.getValue().path("body"));
            body.put("model", "claude-sonnet");
            body.put("stream", false);
            PROVIDER.respond(200, ANSWER);

            HttpResponse<String> answer = post(
                    "/anthropic" + path,
                    body.toString(),
                    headers(call.getValue().path("headers")));

            assertThat(answer.statusCode())
                    .as(call.getKey() + " " + answer.body())
                    .isEqualTo(200);
        }
    }

    private static String[] headers(JsonNode recorded) {
        List<String> headers = new ArrayList<>(List.of("Authorization", "Bearer " + AGENT_KEY.value()));
        for (String name : List.of("anthropic-version", "anthropic-beta")) {
            if (recorded.path(name).isArray()) {
                List<String> values = new ArrayList<>();
                recorded.path(name).forEach(value -> values.add(value.asString()));
                headers.add(name);
                headers.add(String.join(",", values));
            }
        }
        return headers.toArray(String[]::new);
    }

    private static JsonNode example(JsonNode shape) {
        for (Map.Entry<String, JsonNode> kind : shape.properties()) {
            if (kind.getKey().startsWith("object")) {
                ObjectNode object = NODES.objectNode();
                kind.getValue().properties().forEach(field -> object.set(field.getKey(), example(field.getValue())));
                return object;
            }
        }
        if (shape.has("array")) {
            ArrayNode array = NODES.arrayNode();
            shape.path("array")
                    .properties()
                    .forEach(kind -> array.add(example(NODES.objectNode().set(kind.getKey(), kind.getValue()))));
            return array;
        }
        if (shape.path("string").isArray()) {
            return shape.path("string").get(0);
        }
        if (shape.has("string")) {
            return NODES.stringNode("text");
        }
        if (shape.has("number")) {
            return NODES.numberNode(1);
        }
        if (shape.has("boolean")) {
            return NODES.booleanNode(true);
        }
        if (shape.has("opaque")) {
            return NODES.objectNode();
        }
        return NODES.nullNode();
    }
}
