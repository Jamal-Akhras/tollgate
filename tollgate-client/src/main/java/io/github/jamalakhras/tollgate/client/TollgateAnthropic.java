package io.github.jamalakhras.tollgate.client;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.HttpResponseFor;
import com.anthropic.errors.AnthropicServiceException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class TollgateAnthropic {

    private TollgateAnthropic() {}

    public static AnthropicOkHttpClient.Builder builder() {
        return create(System.getenv("TOLLGATE_BASE_URL"), System.getenv("TOLLGATE_API_KEY"));
    }

    public static AnthropicOkHttpClient.Builder builder(String baseUrl, String apiKey) {
        return create(baseUrl, apiKey);
    }

    private static AnthropicOkHttpClient.Builder create(@Nullable String baseUrl, @Nullable String apiKey) {
        return AnthropicOkHttpClient.builder()
                .baseUrl(Tollgate.baseUrl(baseUrl) + "/anthropic")
                .apiKey(Tollgate.apiKey(apiKey));
    }

    public static Receipt receipt(HttpResponseFor<?> response) {
        return Receipt.of(name -> response.headers().values(name));
    }

    public static Optional<Refusal> refusal(AnthropicServiceException e) {
        return Refusal.of(name -> e.headers().values(name), e.statusCode(), e.getMessage());
    }
}
