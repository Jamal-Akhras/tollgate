package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.gateway.ConfigOperation;
import io.github.jamalakhras.tollgate.gateway.EditRejected;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.server.admin.AdminResponses.EditFailures;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.security.SecureRandom;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final SecureRandom random = new SecureRandom();

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
    @EditFailures
    ConfigChange guardMode(
            @PathVariable String policy,
            @PathVariable int index,
            @RequestBody GuardMode body,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestAttribute(AdminCaller.ATTRIBUTE) AdminCaller caller) {
        return change(caller, ifMatch, ConfigEdits.guardMode(policy, index, body.mode()));
    }

    @PutMapping("/tenants/{tenant}/budget")
    @Operation(
            summary = "Set a tenant's budget",
            description = "Rewrites tollgate.yaml with the tenant's budget, in the config's currency, and swaps the"
                    + " new configuration in. It sets a budget on a tenant that had none and replaces one that"
                    + " existed. What is already spent stays counted when only the limit changes; changing the"
                    + " period counts the spend again from the audit trail. Needs If-Match with the config"
                    + " version, and"
                    + " TOLLGATE_ADMIN_EDITS=file.")
    @ApiResponse(responseCode = "200", description = "The version now running, and whether the file changed")
    @ApiResponse(
            responseCode = "400",
            description = "The body, the limit or the period is not valid",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such tenant",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @EditFailures
    ConfigChange budget(
            @PathVariable String tenant,
            @RequestBody BudgetChange body,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestAttribute(AdminCaller.ATTRIBUTE) AdminCaller caller) {
        return change(caller, ifMatch, ConfigEdits.budget(tenant, body.limit(), body.period()));
    }

    @PostMapping("/tenants/{tenant}/keys")
    @Operation(
            summary = "Create a key for a tenant",
            description = "Makes a key as 'tollgate keys create' does, writes only its id, tenant and hash to"
                    + " tollgate.yaml, and swaps the new configuration in. The key is in this response and nowhere"
                    + " else: it is not logged, not kept and cannot be asked for again. Needs If-Match with the"
                    + " config version, and TOLLGATE_ADMIN_EDITS=file.")
    @ApiResponse(responseCode = "201", description = "The new key, shown once")
    @ApiResponse(
            responseCode = "404",
            description = "No such tenant",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @EditFailures
    ResponseEntity<CreatedKey> createKey(
            @PathVariable String tenant,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestAttribute(AdminCaller.ATTRIBUTE) AdminCaller caller) {
        ApiKey key = ApiKey.generate(random);
        ConfigChange change = change(caller, ifMatch, ConfigEdits.addKey(tenant, key.id(), key.sha256()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .cacheControl(CacheControl.noStore())
                .body(new CreatedKey(key.value(), key.id(), tenant, change.version(), change.previous()));
    }

    @DeleteMapping("/keys/{id}")
    @Operation(
            summary = "Revoke a key",
            description = "Removes the key's entry from tollgate.yaml and swaps the new configuration in. The key gets"
                    + " 401 from then on; calls already running finish. A tenant's last key may be revoked. Needs"
                    + " If-Match with the config version, and TOLLGATE_ADMIN_EDITS=file.")
    @ApiResponse(responseCode = "200", description = "The version now running, and whether the file changed")
    @ApiResponse(
            responseCode = "404",
            description = "No key with that ID",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @EditFailures
    ConfigChange revokeKey(
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) @Nullable String ifMatch,
            @RequestAttribute(AdminCaller.ATTRIBUTE) AdminCaller caller) {
        return change(caller, ifMatch, ConfigEdits.removeKey(id));
    }

    private ConfigChange change(AdminCaller caller, @Nullable String ifMatch, ConfigOperation operation) {
        Gateway.Reload reload = editor.apply(caller, ifMatch, List.of(operation));
        return new ConfigChange(reload.version(), ConfigEditor.unquoted(String.valueOf(ifMatch)), reload.changed());
    }

    @ExceptionHandler
    ResponseEntity<AdminError> editsOff(ConfigEditor.EditsOff e) {
        return editsOff();
    }

    private static ResponseEntity<AdminError> editsOff() {
        return error(
                HttpStatus.CONFLICT,
                "edits_off",
                "Edits are off. Set TOLLGATE_ADMIN_EDITS=file to let the admin API rewrite tollgate.yaml");
    }

    private ResponseEntity<AdminError> invalid(String message) {
        return editor.on() ? error(HttpStatus.BAD_REQUEST, "invalid_request", message) : editsOff();
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
    ResponseEntity<AdminError> notFound(ConfigEdits.NotFound e) {
        return error(HttpStatus.NOT_FOUND, "not_found", String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> badEdit(ConfigEdits.BadEdit e) {
        return invalid(String.valueOf(e.getMessage()));
    }

    @ExceptionHandler
    ResponseEntity<AdminError> unreadable(HttpMessageNotReadableException e) {
        return invalid("The request body is not valid");
    }

    @ExceptionHandler
    ResponseEntity<AdminError> mismatch(MethodArgumentTypeMismatchException e) {
        return invalid(e.getName() + " must be a number");
    }

    private static ResponseEntity<AdminError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new AdminError(code, message));
    }

    record GuardMode(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"enforce", "monitor"})
            String mode) {}

    record BudgetChange(
            @Schema(requiredMode = RequiredMode.REQUIRED, description = "A decimal, not negative", example = "50")
            String limit,

            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    allowableValues = {"daily", "monthly", "total"})
            String period) {}

    record CreatedKey(
            @Schema(
                    requiredMode = RequiredMode.REQUIRED,
                    description = "The whole key. It is in this response and nowhere else",
                    example = "tg_EXAMPLEKEYID_0000000000000000000000000000000000000000000")
            String key,

            @Schema(requiredMode = RequiredMode.REQUIRED) String id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String tenant,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "SHA-256 of the configuration now running")
            String version,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "The version the edit was made against")
            String previous) {}

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
