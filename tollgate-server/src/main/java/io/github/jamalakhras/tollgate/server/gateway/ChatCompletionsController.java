package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ChatCompletionsController {

    private final Gateway gateway;

    ChatCompletionsController(Gateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping(Endpoints.OPENAI_CHAT)
    @Nullable
    ResponseEntity<byte[]> complete(
            @RequestAttribute(TraceIdFilter.ATTRIBUTE) String traceId,
            @RequestHeader HttpHeaders headers,
            InputStream body,
            HttpServletResponse response) {
        return gateway.complete(
                        traceId,
                        WireFormat.OPENAI,
                        Relay.credential(headers),
                        body,
                        headers::getFirst,
                        new ServletStream(response))
                .map(Relay::response)
                .orElse(null);
    }
}
