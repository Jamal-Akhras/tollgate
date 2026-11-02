package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.json.JsonMapper;

final class StreamErrors {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private StreamErrors() {}

    static byte[] event(WireFormat format, String message) {
        String event = switch (format) {
            case OPENAI ->
                "data: "
                        + JSON.writeValueAsString(
                                new OpenAiErrorHandler.OpenAiError(new OpenAiErrorHandler.OpenAiError.Detail(
                                        message, "server_error", null, "stream_interrupted")))
                        + "\n\n";
            case ANTHROPIC ->
                "event: error\ndata: "
                        + JSON.writeValueAsString(AnthropicErrorHandler.AnthropicError.of("api_error", message))
                        + "\n\n";
        };
        return event.getBytes(StandardCharsets.UTF_8);
    }
}
