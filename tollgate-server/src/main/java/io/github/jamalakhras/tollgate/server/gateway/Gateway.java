package io.github.jamalakhras.tollgate.server.gateway;

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
import io.github.jamalakhras.tollgate.core.config.WireFormat;
import io.github.jamalakhras.tollgate.core.money.Money;
import io.github.jamalakhras.tollgate.core.money.Usage;
import io.github.jamalakhras.tollgate.core.policy.Decision;
import io.github.jamalakhras.tollgate.providers.ChatRequest;
import io.github.jamalakhras.tollgate.providers.InvalidRequestException;
import io.github.jamalakhras.tollgate.providers.ProviderClient;
import io.github.jamalakhras.tollgate.providers.ProviderResponse;
import io.github.jamalakhras.tollgate.providers.ProviderUnavailableException;
import io.github.jamalakhras.tollgate.providers.UsageReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Gateway {

    static final String DATA_CLASS_HEADER = "X-Tollgate-Data-Class";
    static final long PROMPT_OVERHEAD_TOKENS = 1_024;

    private static final Logger log = LoggerFactory.getLogger(Gateway.class);

    private final GatewayConfig config;
    private final KeyRing keys;
    private final Map<String, String> providerKeys;
    private final ProviderClient client;
    private final BudgetStore budgets;
    private final int maxRequestBytes;

    Gateway(
            GatewayConfig config,
            Function<String, @Nullable String> environment,
            ProviderClient client,
            BudgetStore budgets,
            int maxRequestBytes) {
        this.config = config;
        this.keys = new KeyRing(config.keys());
        this.providerKeys = resolveProviderKeys(config, environment);
        this.client = client;
        this.budgets = budgets;
        this.maxRequestBytes = maxRequestBytes;
    }

    ProviderResponse complete(
            WireFormat endpoint,
            Optional<String> credential,
            InputStream body,
            Function<String, @Nullable String> clientHeaders) {
        String tenant = authenticate(credential);
        byte[] requestBody = read(body);
        ChatRequest request = parse(requestBody);
        if (request.isStreaming()) {
            throw GatewayException.streamingNotSupported();
        }
        Route route = config.route(tenant, request.model())
                .orElseThrow(() -> GatewayException.modelNotFound(request.model()));
        if (route.provider().format() != endpoint) {
            throw GatewayException.wrongEndpoint(route.model(), route.provider().format());
        }
        Admission admission = config.admit(tenant, route, clientHeaders.apply(DATA_CLASS_HEADER), requestBody.length);
        if (admission.decision() instanceof Decision.Deny denial) {
            throw GatewayException.refused(denial);
        }
        ChatRequest.Forwarded forwarded = forward(request, route, endpoint);
        Reservation reservation = reserve(tenant, route, forwarded);
        Money charge = reservation == null ? nothing(route) : reservation.amount();
        try {
            ProviderResponse response = client.send(
                    route.provider(), providerKeys.get(route.providerName()), forwarded.body(), clientHeaders);
            charge = costOf(route, endpoint, response, charge);
            if (response.status() == 401 || response.status() == 403) {
                log.error(
                        "Provider {} rejected Tollgate's credentials with status {}",
                        route.providerName(),
                        response.status());
                throw GatewayException.providerRejectedCredentials();
            }
            return response;
        } catch (ProviderUnavailableException e) {
            if (!e.timedOut()) {
                charge = nothing(route);
            }
            log.warn("Provider {} is unavailable: {}", route.providerName(), e.getMessage());
            throw GatewayException.providerUnavailable(String.valueOf(e.getMessage()), e.timedOut());
        } finally {
            if (reservation != null) {
                budgets.settle(reservation, charge);
            }
        }
    }

    List<String> models(WireFormat endpoint, Optional<String> credential) {
        return config.routes(authenticate(credential)).stream()
                .filter(route -> route.provider().format() == endpoint)
                .map(Route::model)
                .toList();
    }

    private String authenticate(Optional<String> credential) {
        return credential.flatMap(keys::tenantOf).orElseThrow(GatewayException::invalidApiKey);
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
                    route.upstream(), endpoint, route.definition().maxOutputTokens());
        } catch (InvalidRequestException e) {
            throw GatewayException.invalidRequest(String.valueOf(e.getMessage()), e.param());
        }
    }

    private @Nullable Reservation reserve(String tenant, Route route, ChatRequest.Forwarded forwarded) {
        Tenant caller = config.tenants().get(tenant);
        Budget budget = caller == null ? null : caller.budget();
        if (budget == null) {
            return null;
        }
        long maxOutputTokens = forwarded
                .maxOutputTokens()
                .orElseThrow(() -> new IllegalStateException("Budgeted model " + route.model() + " has no output cap"));
        Money mostItCanCost =
                route.definition().price().mostFor(forwarded.size() + PROMPT_OVERHEAD_TOKENS, maxOutputTokens);
        return budgets.reserve(tenant, budget, mostItCanCost)
                .orElseThrow(() -> GatewayException.budgetExceeded(budget, mostItCanCost));
    }

    private static Money costOf(Route route, WireFormat format, ProviderResponse response, Money worstCase) {
        if (response.status() < 200 || response.status() >= 300) {
            return nothing(route);
        }
        Optional<Usage> usage = UsageReader.read(format, response.body());
        if (usage.isEmpty()) {
            log.warn("Provider {} reported no token usage; charging the full reservation", route.providerName());
            return worstCase;
        }
        return route.definition().price().costOf(usage.get());
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
}
