package io.github.jamalakhras.tollgate.server.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Optional;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Instance", description = "About this running instance")
@RequestMapping(path = "/admin/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class AdminInfoController {

    private final AdminInfo info;

    AdminInfoController(Optional<BuildProperties> build) {
        String version = build.map(BuildProperties::getVersion).orElse("unknown");
        this.info = new AdminInfo("tollgate", version);
    }

    @GetMapping("/info")
    @Operation(summary = "Identify this Tollgate instance")
    AdminInfo info() {
        return info;
    }

    record AdminInfo(
            @Schema(requiredMode = RequiredMode.REQUIRED, example = "tollgate")
            String service,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "0.1.0")
            String version) {}
}
