package io.github.jamalakhras.tollgate.gateway;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AnthropicModelsController {

    private final Gateway gateway;

    AnthropicModelsController(Gateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping(Endpoints.ANTHROPIC_MODELS)
    ModelList list(@RequestHeader HttpHeaders headers) {
        List<ModelEntry> models = gateway.models(WireFormat.ANTHROPIC, Relay.credential(headers)).stream()
                .map(ModelEntry::new)
                .toList();
        return new ModelList(
                models,
                false,
                models.isEmpty() ? null : models.getFirst().id(),
                models.isEmpty() ? null : models.getLast().id());
    }

    record ModelList(
            List<ModelEntry> data,
            @JsonProperty("has_more") boolean hasMore,
            @JsonProperty("first_id") @Nullable String firstId,
            @JsonProperty("last_id") @Nullable String lastId) {}

    record ModelEntry(
            String type,
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("created_at") String createdAt) {

        ModelEntry(String id) {
            this("model", id, id, "1970-01-01T00:00:00Z");
        }
    }
}
