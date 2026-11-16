package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.gateway.ConfigOperation;
import io.github.jamalakhras.tollgate.gateway.EditRejected;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.gateway.ReloadRejected;
import io.github.jamalakhras.tollgate.server.admin.ConfigController.AdminError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestController
@Tag(name = "Configuration", description = "The configuration this instance runs")
@RequestMapping(path = "/admin/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class ConfigEditController {

    private final ConfigEditor editor;

    ConfigEditController(ConfigEditor editor) {
        this.editor = editor;
    }

    @PutMapping("/policies/{policy}/guards/{index}/mode")
    @Operation(
            summary = "Switch a guard between enforce and monitor",
            description = "Rewrites tollgate.yaml and swaps the new configuration in. The guard is named by its"
                    + " policy and its position under guards, from 0, as GET /admin/v1/config lists them. Needs"
                    + " If-Match with the config version, and TOLLGATE_ADMIN_EDITS=file. Asking for the mode the"
                    + " guard already has writes nothing.")
    @ApiResponse(responseCode = "200", description = "The version now running, and whether the file changed")
    @ApiResponse(
            responseCode = "400",
            description = "The body or the mode is not valid",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such policy or guard",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Edits are off, or the change needs a restart",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "412",
            description = "If-Match is not the running version, or the file is no longer the running one",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The changed file is not a valid configuration",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "428",
            description = "If-Match is missing",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "500",
            description = "The file could not be written; nothing changed",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    ConfigChange guardMode(
            @PathVariable String policy,
            @PathVariable int index,
            @RequestBody GuardMode body,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestAttribute(AdminCaller.ATTRIBUTE) AdminCaller caller) {
        return change(caller, ifMatch, ConfigEdits.guardMode(policy, index, body.mode()));
    }

    private ConfigChange change(AdminCaller caller, @Nullable String ifMatch, ConfigOperation operation) {
        Gateway.Reload reload = editor.apply(caller, ifMatch, List.of(operation));
        return new ConfigChange(reload.version(), ConfigEditor.unquoted(String.valueOf(ifMatch)), reload.changed());
    }

    @ExceptionHandler
    ResponseEntity<AdminError> editsOff(ConfigEditor.EditsOff e) {
        return error(
                HttpStatus.CONFLICT,
                "edits_off",
                "Edits are off. Set TOLLGATE_ADMIN_EDITS=file to let the admin API rewrite tollgate.yaml");
    }

    @ExceptionHandler
    ResponseEntity<AdminError> noVersion(ConfigEditor.NoVersion e) {
        return error(
                HttpStatus.PRECONDITION_REQUIRED,
                "precondition_required",
                "Send If-Match with the config version from GET /admin/v1/config");
    }

    @ExceptionHandler
    ResponseEntity<AdminError> rejected(EditRejected e) {
        HttpStatus status = e.code().equals(EditRejected.CONFIG_CHANGED)
                ? HttpStatus.PRECONDITION_FAILED
                : HttpStatus.INTERNAL_SERVER_ERROR;
        return error(status, e.code(), String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> rejected(ReloadRejected e) {
        HttpStatus status = e.code().equals(ReloadRejected.RESTART_REQUIRED)
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_CONTENT;
        return error(status, e.code(), String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> notFound(ConfigEdits.NotFound e) {
        return error(HttpStatus.NOT_FOUND, "not_found", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> badEdit(ConfigEdits.BadEdit e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", "The request body is not valid");
    }

    @ExceptionHandler
    ResponseEntity<AdminError> mismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_request", e.getName() + " must be a number");
    }

    private static ResponseEntity<AdminError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new AdminError(code, message));
    }

    record GuardMode(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"enforce", "monitor"})
            String mode) {}

    record ConfigChange(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "SHA-256 of the configuration now running")
            String version,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The version the edit was made against")
            String previous,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "False when the config already said this; nothing was written")
            boolean changed) {}
}
