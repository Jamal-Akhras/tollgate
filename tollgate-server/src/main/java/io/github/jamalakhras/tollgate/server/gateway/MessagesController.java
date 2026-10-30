package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class MessagesController {

    private final Gateway gateway;

    MessagesController(Gateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping(Endpoints.ANTHROPIC_MESSAGES)
    ResponseEntity<byte[]> create(@RequestHeader HttpHeaders headers, InputStream body) {
        return Relay.response(
                gateway.complete(WireFormat.ANTHROPIC, Relay.credential(headers), body, headers::getFirst));
    }
}
