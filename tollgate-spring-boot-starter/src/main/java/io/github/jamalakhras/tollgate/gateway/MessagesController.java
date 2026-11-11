package io.github.jamalakhras.tollgate.gateway;

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
class MessagesController {

    private final Gateway gateway;

    MessagesController(Gateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping(Endpoints.ANTHROPIC_MESSAGES)
    @Nullable
    ResponseEntity<byte[]> create(
            @RequestAttribute(TraceIdFilter.ATTRIBUTE) String traceId,
            @RequestHeader HttpHeaders headers,
            InputStream body,
            HttpServletResponse response) {
        return gateway.complete(
                        traceId,
                        WireFormat.ANTHROPIC,
                        Relay.credential(headers),
                        body,
                        headers::getFirst,
                        new ServletStream(response))
                .map(Relay::response)
                .orElse(null);
    }
}
