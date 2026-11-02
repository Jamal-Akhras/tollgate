package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.AuditUnavailableException;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.auth.KeyRing;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.BudgetStore;
import io.github.jamalakhras.tollgate.core.budget.Reservation;
import io.github.jamalakhras.tollgate.core.config.Admission;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig.Route;
import io.github.jamalakhras.tollgate.core.config.Provider;
import io.github.jamalakhras.tollgate.core.config.SecretRef;
import io.github.jamalakhras.tollgate.core.config.Tenant;
import io.github.jamalakhras.tollgate.core.config.TenantKey;
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.core.policy.Refusal;
import io.github.jamalakhras.tollgate.providers.ChatRequest;
import io.github.jamalakhras.tollgate.providers.InvalidRequestException;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.github.jamalakhras.tollgate.providers.ProviderResponse;
import io.github.jamalakhras.tollgate.providers.ProviderUnavailableException;
import io.github.jamalakhras.tollgate.providers.UsageReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Gateway implements AutoCloseable {

    static final String DATA_CLASS_HEADER = "X-Tollgate-Data-Class";
    static final String ANTHROPIC_BETA_HEADER = "anthropic-beta";
    static final long PROMPT_OVERHEAD_TOKENS = 1_024;

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

    private final Function<String, @Nullable String> environment;
    private final ProviderClient client;
    private final BudgetStore budgets;
    private final @Nullable AuditLog audit;
    private final Clock clock;
    private final int maxRequestBytes;
    private volatile Snapshot current;

    Gateway(
            LoadedConfig loaded,
            Function<String, @Nullable String> environment,
            ProviderClient client,
            BudgetStore budgets,
            @Nullable AuditLog audit,
            Clock clock,
            int maxRequestBytes) {
        this.environment = environment;
        this.current = new Snapshot(loaded, environment);
        this.client = client;
        this.budgets = budgets;
        this.audit = audit;
        this.clock = clock;
        this.maxRequestBytes = maxRequestBytes;
    }

    ProviderResponse complete(
            String traceId,
            WireFormat endpoint,
            Optional<String> credential,
            InputStream body,
            Function<String, @Nullable String> clientHeaders) {
        Snapshot snapshot = current;
        TenantKey caller = snapshot.authenticate(credential);
        byte[] requestBody = read(body);
        ChatRequest request = parse(requestBody);
        if (request.isStreaming()) {
            throw GatewayException.streamingNotSupported();
        }
        GatewayConfig config = snapshot.config();
        Route route = config.route(caller.tenant(), request.model())
                .orElseThrow(() -> GatewayException.modelNotFound(request.model()));
        if (route.provider().format() != endpoint) {
            throw GatewayException.wrongEndpoint(route.model(), route.provider().format());
        }
        Admission admission = config.admit(
                caller.tenant(),
                route,
                clientHeaders.apply(DATA_CLASS_HEADER),
                requestBody.length,
                request.review(endpoint, clientHeaders.apply(ANTHROPIC_BETA_HEADER)));
        Call call = new Call(traceId, snapshot, caller, endpoint, route, admission);
        if (admission.decision() instanceof Decision.Deny denial) {
            throw refuse(call, requestBody, denial);
        }
        ChatRequest.Forwarded forwarded = forward(request, route, endpoint);
        Reservation reservation = reserve(call, requestBody, forwarded);
        try {
            recordIntent(call, requestBody, Decision.ALLOW, reservation == null ? null : reservation.amount());
        } catch (GatewayException e) {
            if (reservation != null) {
                budgets.settle(reservation, nothing(route));
            }
            throw e;
        }
        return send(call, forwarded, reservation, clientHeaders);
    }

    List<String> models(WireFormat endpoint, Optional<String> credential) {
        Snapshot snapshot = current;
        return snapshot.config().routes(snapshot.authenticate(credential).tenant()).stream()
                .filter(route -> route.provider().format() == endpoint)
                .map(Route::model)
                .toList();
    }

    synchronized Reload reload() {
        Snapshot before = current;
        LoadedConfig next;
        try {
            next = ConfigFile.read(before.loaded().path());
        } catch (IllegalStateException | UncheckedIOException e) {
            throw new ReloadRejected(ReloadRejected.INVALID_CONFIG, String.valueOf(e.getMessage()));
        }
        if (next.version().equals(before.loaded().version())) {
            return new Reload(next.version(), false);
        }
        if (!next.config().audit().equals(before.config().audit())) {
            throw new ReloadRejected(
                    ReloadRejected.RESTART_REQUIRED,
                    "The audit settings changed; restart Tollgate to apply them. The running config is unchanged");
        }
        Snapshot after;
        try {
            after = new Snapshot(next, environment);
        } catch (IllegalStateException e) {
            throw new ReloadRejected(ReloadRejected.INVALID_CONFIG, String.valueOf(e.getMessage()));
        }
        current = after;
        log.info(
                "Reloaded {}: config version {} replaces {}",
                next.path(),
                next.version(),
                before.loaded().version());
        return new Reload(next.version(), true);
    }

    boolean auditHealthy() {
        return audit == null || audit.healthy();
    }

    @Override
    public void close() throws IOException {
        if (audit != null) {
            audit.close();
        }
    }

    private ProviderResponse send(
            Call call,
            ChatRequest.Forwarded forwarded,
            @Nullable Reservation reservation,
            Function<String, @Nullable String> clientHeaders) {
        Route route = call.route();
        Money charge = reservation == null ? nothing(route) : reservation.amount();
        Outcome.Result result = Outcome.Result.UNREACHABLE;
        ProviderResponse response = null;
        Optional<Usage> usage = Optional.empty();
        Instant started = clock.instant();
        try {
            response = client.send(
                    route.provider(),
                    call.snapshot().providerKeys().get(route.providerName()),
                    forwarded.body(),
                    clientHeaders);
            result = Outcome.Result.RESPONDED;
            if (succeeded(response)) {
                usage = UsageReader.read(call.endpoint(), response.body());
            }
            charge = costOf(route, response, usage, charge);
            if (response.status() == 401 || response.status() == 403) {
                log.error(
                        "Provider {} rejected Tollgate's credentials with status {}",
                        route.providerName(),
                        response.status());
                throw GatewayException.providerRejectedCredentials();
            }
            return response;
        } catch (ProviderUnavailableException e) {
            result = e.timedOut() ? Outcome.Result.TIMED_OUT : Outcome.Result.UNREACHABLE;
            if (!e.timedOut()) {
                charge = nothing(route);
            }
            log.warn("Provider {} is unavailable: {}", route.providerName(), e.getMessage());
            throw GatewayException.providerUnavailable(String.valueOf(e.getMessage()), e.timedOut());
        } finally {
            if (reservation != null) {
                budgets.settle(reservation, charge);
            }
            recordOutcome(call, result, response, usage, charge, Duration.between(started, clock.instant()));
        }
    }

    private byte[] read(InputStream body) {
        try {
            byte[] bytes = body.readNBytes(maxRequestBytes + 1);
            if (bytes.length > maxRequestBytes) {
                throw GatewayException.requestTooLarge(maxRequestBytes);
            }
            return bytes;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the request body", e);
        }
    }

    private static ChatRequest parse(byte[] body) {
        try {
            return ChatRequest.parse(body);
        } catch (InvalidRequestException e) {
            throw GatewayException.invalidRequest(String.valueOf(e.getMessage()), e.param());
        }
    }

    private static ChatRequest.Forwarded forward(ChatRequest request, Route route, WireFormat endpoint) {
        try {
            return request.forward(
                    route.upstream(),
                    endpoint,
                    route.definition().maxOutputTokens(),
                    route.definition().maxWebSearches());
        } catch (InvalidRequestException e) {
            throw GatewayException.invalidRequest(String.valueOf(e.getMessage()), e.param());
        }
    }

    private @Nullable Reservation reserve(Call call, byte[] requestBody, ChatRequest.Forwarded forwarded) {
        Route route = call.route();
        Tenant tenant = call.snapshot().config().tenants().get(call.caller().tenant());
        Budget budget = tenant == null ? null : tenant.budget();
        if (budget == null) {
            return null;
        }
        long maxOutputTokens = forwarded
                .maxOutputTokens()
                .orElseThrow(() -> new IllegalStateException("Budgeted model " + route.model() + " has no output cap"));
        Money mostItCanCost = route.definition()
                .price()
                .mostFor(forwarded.size() + PROMPT_OVERHEAD_TOKENS, maxOutputTokens, forwarded.webSearches());
        Optional<Reservation> reservation = budgets.reserve(call.caller().tenant(), budget, mostItCanCost);
        if (reservation.isEmpty()) {
            throw refuse(
                    call,
                    requestBody,
                    new Decision.Deny(
                            Refusal.BUDGET_EXCEEDED,
                            "This request could cost up to " + mostItCanCost + ", which this tenant's "
                                    + budget.period().configName() + " budget of " + budget.limit()
                                    + " cannot cover"));
        }
        return reservation.get();
    }

    private GatewayException refuse(Call call, byte[] requestBody, Decision.Deny denial) {
        recordIntent(call, requestBody, denial, null);
        return GatewayException.refused(denial);
    }

    private void recordIntent(Call call, byte[] requestBody, Decision decision, @Nullable Money reservation) {
        AuditLog trail = audit;
        if (trail == null) {
            return;
        }
        Route route = call.route();
        Intent intent = new Intent(
                call.traceId(),
                clock.instant(),
                call.caller().tenant(),
                call.caller().id(),
                call.endpoint(),
                route.model(),
                route.providerName(),
                route.upstream(),
                call.admission().dataClass(),
                call.snapshot().loaded().version(),
                trail.hmac(requestBody),
                call.admission().steps(),
                decision,
                reservation);
        try {
            trail.append(intent);
        } catch (AuditUnavailableException e) {
            log.error("Refused request {} because its audit record could not be written", call.traceId(), e);
            throw GatewayException.auditUnavailable();
        }
    }

    private void recordOutcome(
            Call call,
            Outcome.Result result,
            @Nullable ProviderResponse response,
            Optional<Usage> usage,
            Money cost,
            Duration duration) {
        AuditLog trail = audit;
        if (trail == null) {
            return;
        }
        AuditRecord outcome = new Outcome(
                call.traceId(),
                clock.instant(),
                result,
                response == null ? null : response.status(),
                usage.orElse(null),
                cost,
                duration,
                response == null ? null : trail.hmac(response.body()));
        try {
            trail.append(outcome);
        } catch (AuditUnavailableException e) {
            log.error("The outcome of request {} could not be written to the audit trail", call.traceId(), e);
        }
    }

    private static boolean succeeded(ProviderResponse response) {
        return response.status() >= 200 && response.status() < 300;
    }

    private static Money costOf(Route route, ProviderResponse response, Optional<Usage> usage, Money worstCase) {
        if (!succeeded(response)) {
            return nothing(route);
        }
        if (usage.isEmpty()) {
            log.warn("Provider {} reported no token usage; charging the full reservation", route.providerName());
            return worstCase;
        }
        long webSearches = route.definition().offersWebSearch()
                ? UsageReader.webSearches(route.provider().format(), response.body())
                : 0;
        return route.definition().price().costOf(usage.get(), webSearches);
    }

    private static Money nothing(Route route) {
        return new Money(
                0, route.definition().price().input().perMillionTokens().currency());
    }

    private static Map<String, String> resolveProviderKeys(
            GatewayConfig config, Function<String, @Nullable String> environment) {
        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, Provider> provider : config.providers().entrySet()) {
            SecretRef apiKey = provider.getValue().apiKey();
            if (apiKey != null) {
                resolved.put(provider.getKey(), apiKey.resolve(environment));
            }
        }
        return Map.copyOf(resolved);
    }

    record Reload(String version, boolean changed) {}

    private record Call(
            String traceId,
            Snapshot snapshot,
            TenantKey caller,
            WireFormat endpoint,
            Route route,
            Admission admission) {}

    private record Snapshot(LoadedConfig loaded, KeyRing keys, Map<String, String> providerKeys) {

        Snapshot(LoadedConfig loaded, Function<String, @Nullable String> environment) {
            this(loaded, new KeyRing(loaded.config().keys()), resolveProviderKeys(loaded.config(), environment));
        }

        GatewayConfig config() {
            return loaded.config();
        }

        TenantKey authenticate(Optional<String> credential) {
            return credential.flatMap(keys::authenticate).orElseThrow(GatewayException::invalidApiKey);
        }
    }
}
