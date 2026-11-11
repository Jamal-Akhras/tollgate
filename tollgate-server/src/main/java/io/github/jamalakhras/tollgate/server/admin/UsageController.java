package io.github.jamalakhras.tollgate.server.admin;

import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.BudgetStore;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.gateway.Gateway;
import io.github.jamalakhras.tollgate.gateway.audit.UsageReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Usage", description = "What each tenant used and spent")
@RequestMapping(path = "/admin/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class UsageController {

    private final Gateway gateway;

    UsageController(Gateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping("/usage")
    @Operation(
            summary = "Sum the audit trail",
            description = "Counts calls, refusals, tokens and cost from the signed audit trail, by the day each"
                    + " request arrived in UTC. Each provider attempt is a call, so a request that fell back to"
                    + " another model counts twice. Dry runs are not counted.")
    @ApiResponse(responseCode = "200", description = "The usage in the period")
    @ApiResponse(
            responseCode = "400",
            description = "A date or grouping is not valid",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "The audit trail is off, so there is nothing to sum",
            content = @Content(schema = @Schema(implementation = ConfigController.AdminError.class)))
    UsageReport usage(
            @Parameter(
                            description = "The first day included, such as 2026-11-01. Defaults to the first of this"
                                    + " month")
                    @RequestParam(required = false)
                    @Nullable
                    String from,
            @Parameter(description = "The first day not included. Defaults to tomorrow")
                    @RequestParam(required = false)
                    @Nullable
                    String to,
            @Parameter(description = "tenant, model, key or day") @RequestParam(defaultValue = "tenant") String by)
            throws IOException {
        Path directory = gateway.auditDirectory().orElseThrow(AuditOff::new);
        try {
            return UsageReport.of(directory, from, to, by, Clock.systemUTC());
        } catch (IllegalArgumentException e) {
            throw new BadQuery(String.valueOf(e.getMessage()));
        }
    }

    @GetMapping("/budgets")
    @Operation(
            summary = "Show each budget",
            description = "The current period of every tenant with a budget, live from this instance.")
    List<BudgetState> budgets() {
        BudgetStore store = gateway.budgetStore();
        Map<String, BudgetState> states = new TreeMap<>();
        gateway.config().tenants().forEach((tenant, settings) -> {
            Budget budget = settings.budget();
            if (budget != null) {
                states.put(
                        tenant,
                        new BudgetState(
                                tenant,
                                budget.period().configName(),
                                budget.limit().currency().getCurrencyCode(),
                                amount(budget.limit()),
                                amount(store.spent(tenant, budget)),
                                amount(store.reserved(tenant, budget)),
                                amount(store.available(tenant, budget))));
            }
        });
        return List.copyOf(states.values());
    }

    private static String amount(Money money) {
        return money.toBigDecimal().stripTrailingZeros().toPlainString();
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> badQuery(BadQuery e) {
        return ResponseEntity.badRequest()
                .body(new ConfigController.AdminError("invalid_request", String.valueOf(e.getMessage())));
    }

    @ExceptionHandler
    ResponseEntity<ConfigController.AdminError> auditOff(AuditOff e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ConfigController.AdminError("audit_off", String.valueOf(e.getMessage())));
    }

    record BudgetState(
            @Schema(requiredMode = RequiredMode.REQUIRED, example = "support-bot")
            String tenant,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "monthly")
            String period,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "USD")
            String currency,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "50")
            String limit,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "12.345")
            String spent,

            @Schema(requiredMode = RequiredMode.REQUIRED, description = "Held for calls in flight", example = "0.05")
            String reserved,

            @Schema(requiredMode = RequiredMode.REQUIRED, example = "37.605")
            String available) {}

    static final class BadQuery extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BadQuery(String message) {
            super(message);
        }
    }

    static final class AuditOff extends RuntimeException {

        private static final long serialVersionUID = 1L;

        AuditOff() {
            super("The usage report sums the audit trail, and audit.mode is off");
        }
    }
}
