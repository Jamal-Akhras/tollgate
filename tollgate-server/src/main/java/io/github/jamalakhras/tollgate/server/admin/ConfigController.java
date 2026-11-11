package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.gateway.ReloadRejected;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Configuration", description = "The configuration this instance runs")
@RequestMapping(path = "/admin/v1/config", produces = MediaType.APPLICATION_JSON_VALUE)
class ConfigController {

    private final Gateway gateway;

    ConfigController(Gateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/reload")
    @Operation(
            summary = "Reload the configuration file",
            description = "Reads tollgate.yaml again, validates it and swaps it in atomically. Requests already"
                    + " running finish under the configuration they started with. An invalid file never replaces"
                    + " the running configuration.")
    @ApiResponse(responseCode = "200", description = "The file was read; changed says whether it differed")
    @ApiResponse(
            responseCode = "409",
            description = "The audit settings changed, which needs a restart",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The file is not a valid configuration",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    ReloadResult reload() {
        Gateway.Reload reload = gateway.reload();
        return new ReloadResult(reload.version(), reload.changed());
    }

    @ExceptionHandler
    ResponseEntity<AdminError> rejected(ReloadRejected e) {
        HttpStatus status = e.code().equals(ReloadRejected.RESTART_REQUIRED)
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return ResponseEntity.status(status).body(new AdminError(e.code(), String.valueOf(e.getMessage())));
    }

    record ReloadResult(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "SHA-256 of the configuration file now in use",
                    example = "7f6d9cc74d1a0e3b5b8f3c2f1e4a6d9b0c8e7f6a5d4c3b2a1908f7e6d5c4b3a2")
            String version,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "Whether the file differed from the configuration that was running")
            boolean changed) {}

    record AdminError(
            @Schema(requiredMode = RequiredMode.REQUIRED, example = "invalid_config")
            String code,

            @Schema(requiredMode = RequiredMode.REQUIRED) String message) {}
}
