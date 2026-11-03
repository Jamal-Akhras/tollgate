package io.github.jamalakhras.tollgate.server.gateway;

import io.github.jamalakhras.tollgate.core.audit.AuditLog;
import io.github.jamalakhras.tollgate.core.audit.AuditRecord;
import io.github.jamalakhras.tollgate.core.audit.AuditUnavailableException;
import io.github.jamalakhras.tollgate.core.audit.Fingerprint;
import io.github.jamalakhras.tollgate.core.audit.Intent;
import io.github.jamalakhras.tollgate.core.audit.Outcome;
import io.github.jamalakhras.tollgate.core.auth.KeyRing;
import io.github.jamalakhras.tollgate.core.budget.Budget;
import io.github.jamalakhras.tollgate.core.budget.BudgetStore;
import io.github.jamalakhras.tollgate.core.budget.Reservation;
import io.github.jamalakhras.tollgate.core.config.Admission;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig;
import io.github.jamalakhras.tollgate.core.config.GatewayConfig.Route;
import io.github.jamalakhras.tollgate.core.config.OutputTokens;
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
import io.github.jamalakhras.tollgate.providers.InvalidProviderResponseException;
import io.github.jamalakhras.tollgate.providers.InvalidRequestException;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.github.jamalakhras.tollgate.providers.ProviderResponse;
import io.github.jamalakhras.tollgate.providers.ProviderStream;
import io.github.jamalakhras.tollgate.providers.ProviderUnavailableException;
import io.github.jamalakhras.tollgate.providers.StreamRewriter;
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
import java.util.OptionalLong;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Gateway implements AutoCloseable {

    static final String DATA_CLASS_HEADER = "X-Tollgate-Data-Class";
    static final String ANTHROPIC_BETA_HEADER = "anthropic-beta";
    static final long PROMPT_OVERHEAD_TOKENS = 1_024;
    static final long IMAGE_TOKENS = 5_000;
    static final String OUTPUT_LIMITED_HEADER = "X-Tollgate-Output-Limited";

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

    private final Function<String, @Nullable String> environment;
    private final ProviderClient client;
    private final BudgetStore budgets;
    private final @Nullable AuditLog audit;
    private final Clock clock;
    private final int maxRequestBytes;
    private final Duration maxStreamDuration;
    private volatile Snapshot current;

    Gateway(
            LoadedConfig loaded,
            Function<String, @Nullable String> environment,
            ProviderClient client,
            BudgetStore budgets,
            @Nullable AuditLog audit,
            Clock clock,
            int maxRequestBytes,
            Duration maxStreamDuration) {
        this.environment = environment;
        this.current = new Snapshot(loaded, environment);
        this.client = client;
        this.budgets = budgets;
        this.audit = audit;
        this.clock = clock;
        this.maxRequestBytes = maxRequestBytes;
        this.maxStreamDuration = maxStreamDuration;
    }

    Optional<Result> complete(
            String traceId,
            WireFormat endpoint,
            Optional<String> credential,
            InputStream body,
            Function<String, @Nullable String> clientHeaders,
            StreamTarget target) {
        Snapshot snapshot = current;
        TenantKey caller = snapshot.authenticate(credential);
        byte[] requestBody = read(body);
        ChatRequest request = parse(requestBody);
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
                request.review(
                        endpoint,
                        clientHeaders.apply(ANTHROPIC_BETA_HEADER),
                        route.provider().extraFields()));
        Call call = new Call(traceId, snapshot, caller, endpoint, route, admission);
        if (admission.decision() instanceof Decision.Deny denial) {
            throw refuse(call, requestBody, denial);
        }
        Budgeted budgeted = reserve(call, requestBody, request);
        Reservation reservation = budgeted.reservation();
        try {
            recordIntent(call, requestBody, Decision.ALLOW, reservation == null ? null : reservation.amount());
        } catch (GatewayException e) {
            if (reservation != null) {
                budgets.settle(reservation, nothing(route));
            }
            throw e;
        }
        if (request.isStreaming()) {
            stream(call, budgeted, clientHeaders, target, request.wantsStreamUsage());
            return Optional.empty();
        }
        ProviderResponse response = send(call, budgeted.forwarded(), reservation, clientHeaders);
        return Optional.of(new Result(response, budgeted.outputLimitedTo()));
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
            if (!succeeded(response)) {
                throw unsuccessful(route, response);
            }
            return response.withModel(route.model());
        } catch (InvalidProviderResponseException e) {
            result = Outcome.Result.INVALID_RESPONSE;
            log.warn("Provider {} sent an invalid response: {}", route.providerName(), e.getMessage());
            throw GatewayException.providerInvalidResponse(String.valueOf(e.getMessage()));
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
            ProviderResponse answered = response;
            recordOutcome(
                    call,
                    result,
                    answered == null ? null : answered.status(),
                    usage,
                    charge,
                    Duration.between(started, clock.instant()),
                    trail -> answered == null ? null : trail.hmac(answered.body()));
        }
    }

    private void stream(
            Call call,
            Budgeted budgeted,
            Function<String, @Nullable String> clientHeaders,
            StreamTarget target,
            boolean relayUsage) {
        Route route = call.route();
        Reservation reservation = budgeted.reservation();
        Money charge = reservation == null ? nothing(route) : reservation.amount();
        Outcome.Result result = Outcome.Result.UNREACHABLE;
        Integer status = null;
        Optional<Usage> usage = Optional.empty();
        Fingerprint fingerprint = audit == null ? null : audit.fingerprint();
        Instant started = clock.instant();
        long deadline = System.nanoTime() + maxStreamDuration.toNanos();
        ProviderStream stream = null;
        boolean opened = false;
        try {
            stream = client.stream(
                    route.provider(),
                    call.snapshot().providerKeys().get(route.providerName()),
                    budgeted.forwarded().body(),
                    clientHeaders);
            status = stream.status();
            result = Outcome.Result.RESPONDED;
            if (!succeeded(stream.status())) {
                charge = nothing(route);
                throw unsuccessful(route, stream.whole());
            }
            StreamRewriter rewriter = new StreamRewriter(call.endpoint(), route.model(), relayUsage);
            target.open(budgeted.outputLimitedTo());
            opened = true;
            List<String> event = stream.nextEvent(remaining(deadline));
            while (event != null) {
                Optional<byte[]> relayed = rewriter.rewrite(event);
                if (relayed.isPresent()) {
                    target.write(relayed.get());
                    if (fingerprint != null) {
                        fingerprint.add(relayed.get());
                    }
                }
                event = stream.nextEvent(remaining(deadline));
            }
            usage = rewriter.usage();
            if (usage.isPresent()) {
                long webSearches = route.definition().offersWebSearch() ? rewriter.webSearches() : 0;
                charge = route.definition().price().costOf(usage.get(), rewriter.reasoningTokens(), webSearches);
            } else {
                log.warn("Provider {} streamed no token usage; charging the full reservation", route.providerName());
            }
        } catch (IOException e) {
            result = Outcome.Result.CLIENT_CLOSED;
            log.info("The client of request {} closed the stream", call.traceId());
        } catch (InvalidProviderResponseException e) {
            if (!opened) {
                result = Outcome.Result.INVALID_RESPONSE;
                log.warn("Provider {} sent an invalid response: {}", route.providerName(), e.getMessage());
                throw GatewayException.providerInvalidResponse(String.valueOf(e.getMessage()));
            }
            result = Outcome.Result.INTERRUPTED;
            endEarly(call, target, deadline, e);
        } catch (ProviderUnavailableException e) {
            if (!opened) {
                result = e.timedOut() ? Outcome.Result.TIMED_OUT : Outcome.Result.UNREACHABLE;
                if (!e.timedOut()) {
                    charge = nothing(route);
                }
                log.warn("Provider {} is unavailable: {}", route.providerName(), e.getMessage());
                throw GatewayException.providerUnavailable(String.valueOf(e.getMessage()), e.timedOut());
            }
            result = Outcome.Result.INTERRUPTED;
            endEarly(call, target, deadline, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = Outcome.Result.INTERRUPTED;
        } finally {
            if (stream != null) {
                stream.close();
            }
            if (reservation != null) {
                budgets.settle(reservation, charge);
            }
            String streamed = opened && fingerprint != null ? fingerprint.hex() : null;
            recordOutcome(
                    call, result, status, usage, charge, Duration.between(started, clock.instant()), trail -> streamed);
        }
    }

    private static Duration remaining(long deadline) {
        return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
    }

    private void endEarly(Call call, StreamTarget target, long deadline, RuntimeException cause) {
        String message = System.nanoTime() >= deadline
                ? "The stream lasted longer than " + maxStreamDuration
                : String.valueOf(cause.getMessage());
        log.warn("Stream from provider {} ended early: {}", call.route().providerName(), message);
        try {
            target.write(StreamErrors.event(call.endpoint(), message));
        } catch (IOException e) {
            log.debug("Could not tell the client that request {} ended early", call.traceId(), e);
        }
    }

    private static GatewayException unsuccessful(Route route, ProviderResponse response) {
        if (response.status() == 401 || response.status() == 403) {
            log.error(
                    "Provider {} rejected Tollgate's credentials with status {}",
                    route.providerName(),
                    response.status());
            return GatewayException.providerRejectedCredentials();
        }
        log.warn(
                "Provider {} answered with status {}: {}",
                route.providerName(),
                response.status(),
                response.errorMessage().orElse("no message"));
        return GatewayException.providerError(
                response.status(), response.errorMessage().orElse(null), response.retryAfter());
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

    private static ChatRequest.Forwarded forward(
            ChatRequest request, Route route, WireFormat endpoint, @Nullable OutputTokens limits, boolean budgeted) {
        try {
            return request.forward(
                    route.upstream(), endpoint, limits, route.definition().maxWebSearches(), budgeted);
        } catch (InvalidRequestException e) {
            throw GatewayException.invalidRequest(String.valueOf(e.getMessage()), e.param());
        }
    }

    private Budgeted reserve(Call call, byte[] requestBody, ChatRequest request) {
        Route route = call.route();
        OutputTokens limits = route.definition().maxOutputTokens();
        String tenantName = call.caller().tenant();
        Tenant tenant = call.snapshot().config().tenants().get(tenantName);
        Budget budget = tenant == null ? null : tenant.budget();
        ChatRequest.Forwarded forwarded = forward(request, route, call.endpoint(), limits, budget != null);
        if (budget == null) {
            return new Budgeted(forwarded, null, OptionalLong.empty());
        }
        if (limits == null) {
            throw new IllegalStateException("Budgeted model " + route.model() + " has no output cap");
        }
        Money mostItCanCost = mostItCanCost(route, forwarded);
        Optional<Reservation> reservation = budgets.reserve(tenantName, budget, mostItCanCost);
        if (reservation.isPresent()) {
            return new Budgeted(forwarded, reservation.get(), OptionalLong.empty());
        }
        long fits = route.definition()
                        .price()
                        .outputTokensWithin(
                                budgets.available(tenantName, budget), inputTokens(forwarded), forwarded.webSearches())
                / forwarded.choices();
        long lowest = Math.max(limits.floor(), request.thinkingBudget() + 1);
        long asked = forwarded.maxOutputTokens().orElse(limits.cap());
        if (fits >= lowest && fits < asked) {
            ChatRequest.Forwarded smaller = forward(request, route, call.endpoint(), limits.loweredTo(fits), true);
            Optional<Reservation> lowered = budgets.reserve(tenantName, budget, mostItCanCost(route, smaller));
            if (lowered.isPresent()) {
                return new Budgeted(smaller, lowered.get(), OptionalLong.of(fits));
            }
        }
        throw refuse(
                call,
                requestBody,
                new Decision.Deny(
                        Refusal.BUDGET_EXCEEDED,
                        "This request could cost up to " + mostItCanCost + ", which this tenant's "
                                + budget.period().configName() + " budget of " + budget.limit()
                                + " cannot cover, even with a shorter answer"));
    }

    private static long inputTokens(ChatRequest.Forwarded forwarded) {
        return forwarded.size() + PROMPT_OVERHEAD_TOKENS + Math.multiplyExact(IMAGE_TOKENS, forwarded.inlineImages());
    }

    private static Money mostItCanCost(Route route, ChatRequest.Forwarded forwarded) {
        long output = Math.multiplyExact(forwarded.maxOutputTokens().orElse(0), forwarded.choices());
        return route.definition().price().mostFor(inputTokens(forwarded), output, forwarded.webSearches());
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
            @Nullable Integer status,
            Optional<Usage> usage,
            Money cost,
            Duration duration,
            Function<AuditLog, @Nullable String> responseHmac) {
        AuditLog trail = audit;
        if (trail == null) {
            return;
        }
        AuditRecord outcome = new Outcome(
                call.traceId(),
                clock.instant(),
                result,
                status,
                usage.orElse(null),
                cost,
                duration,
                responseHmac.apply(trail));
        try {
            trail.append(outcome);
        } catch (AuditUnavailableException e) {
            log.error("The outcome of request {} could not be written to the audit trail", call.traceId(), e);
        }
    }

    private static boolean succeeded(ProviderResponse response) {
        return succeeded(response.status());
    }

    private static boolean succeeded(int status) {
        return status >= 200 && status < 300;
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
        long reasoningTokens = UsageReader.reasoningTokens(route.provider().format(), response.body());
        return route.definition().price().costOf(usage.get(), reasoningTokens, webSearches);
    }

    private static Money nothing(Route route) {
        return new Money(0, route.definition().price().currency());
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

    record Result(ProviderResponse response, OptionalLong outputLimitedTo) {}

    private record Budgeted(
            ChatRequest.Forwarded forwarded, @Nullable Reservation reservation, OptionalLong outputLimitedTo) {}

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
