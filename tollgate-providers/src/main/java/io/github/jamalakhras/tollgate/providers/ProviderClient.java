package io.github.jamalakhras.tollgate.providers;

import io.github.jamalakhras.tollgate.core.config.Provider;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class ProviderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";

    private final HttpClient http;
    private final long maxResponseBytes;

    public ProviderClient(HttpClient http, long maxResponseBytes) {
        this.http = http;
        this.maxResponseBytes = maxResponseBytes;
    }

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public ProviderResponse send(
            Provider provider, @Nullable String apiKey, byte[] body, Function<String, @Nullable String> clientHeaders) {
        HttpRequest.Builder request = HttpRequest.newBuilder(provider.endpoint(path(provider)))
                .timeout(provider.timeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if ("http".equals(provider.baseUrl().getScheme())) {
            request.version(HttpClient.Version.HTTP_1_1);
        }
        switch (provider.format()) {
            case OPENAI -> {
                if (apiKey != null) {
                    request.header("Authorization", "Bearer " + apiKey);
                }
            }
            case ANTHROPIC -> {
                if (apiKey != null) {
                    request.header("x-api-key", apiKey);
                }
                String version = clientHeaders.apply("anthropic-version");
                request.header("anthropic-version", version == null ? DEFAULT_ANTHROPIC_VERSION : version);
                String beta = clientHeaders.apply("anthropic-beta");
                if (beta != null) {
                    request.header("anthropic-beta", beta);
                }
            }
        }
        CompletableFuture<HttpResponse<byte[]>> exchange =
                http.sendAsync(request.build(), LimitedBody.handler(maxResponseBytes));
        try {
            HttpResponse<byte[]> response = exchange.get(provider.timeout().toNanos(), TimeUnit.NANOSECONDS);
            return new ProviderResponse(
                    response.statusCode(),
                    response.body(),
                    response.headers().firstValue("Content-Type").orElse(null),
                    response.headers().firstValue("Retry-After").orElse(null));
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw timedOut(provider, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof InvalidProviderResponseException invalid) {
                throw invalid;
            }
            if (cause instanceof HttpTimeoutException) {
                throw timedOut(provider, cause);
            }
            throw new ProviderUnavailableException("The provider could not be reached", false, e);
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("Interrupted while waiting for the provider", false, e);
        }
    }

    private static ProviderUnavailableException timedOut(Provider provider, Throwable cause) {
        return new ProviderUnavailableException(
                "The provider did not answer within " + provider.timeout(), true, cause);
    }

    private static String path(Provider provider) {
        return switch (provider.format()) {
            case OPENAI -> "/chat/completions";
            case ANTHROPIC -> "/v1/messages";
        };
    }
}
