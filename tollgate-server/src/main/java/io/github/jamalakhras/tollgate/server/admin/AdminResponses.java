package io.github.jamalakhras.tollgate.server.admin;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

final class AdminResponses {

    private AdminResponses() {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
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
    @interface EditFailures {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @interface AuditOff {}

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @ApiResponse(
            responseCode = "429",
            description = "Another long read of the trail is running; retry after the Retry-After seconds",
            content = @Content(schema = @Schema(implementation = AdminError.class)))
    @interface AuditBusy {}
}
