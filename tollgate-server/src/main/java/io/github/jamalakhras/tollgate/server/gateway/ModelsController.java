package io.github.jamalakhras.tollgate.server.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ModelsController {

    private final Gateway gateway;

    ModelsController(Gateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping(Endpoints.OPENAI_MODELS)
    ModelList list(@RequestHeader HttpHeaders headers) {
        List<ModelEntry> models = gateway.models(WireFormat.OPENAI, Relay.credential(headers)).stream()
                .map(ModelEntry::new)
                .toList();
        return new ModelList("list", models);
    }

    record ModelList(String object, List<ModelEntry> data) {}

    record ModelEntry(
            String id,
            String object,
            long created,
            @JsonProperty("owned_by") String ownedBy) {

        ModelEntry(String id) {
            this(id, "model", 0, "tollgate");
        }
    }
}
