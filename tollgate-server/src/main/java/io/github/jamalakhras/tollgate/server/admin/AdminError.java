package io.github.jamalakhras.tollgate.server.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

record AdminError(
        @Schema(requiredMode = RequiredMode.REQUIRED, example = "invalid_config")
        String code,

        @Schema(requiredMode = RequiredMode.REQUIRED) String message) {}
