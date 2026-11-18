package io.github.jamalakhras.tollgate.client;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIServiceException;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public final class TollgateOpenAI {

    private TollgateOpenAI() {}

    public static OpenAIOkHttpClient.Builder builder() {
        return create(System.getenv("TOLLGATE_BASE_URL"), System.getenv("TOLLGATE_API_KEY"));
    }

    public static OpenAIOkHttpClient.Builder builder(String baseUrl, String apiKey) {
        return create(baseUrl, apiKey);
    }

    private static OpenAIOkHttpClient.Builder create(@Nullable String baseUrl, @Nullable String apiKey) {
        return OpenAIOkHttpClient.builder()
                .baseUrl(Tollgate.baseUrl(baseUrl) + "/v1")
                .apiKey(Tollgate.apiKey(apiKey));
    }

    public static Receipt receipt(HttpResponseFor<?> response) {
        return Receipt.of(name -> response.headers().values(name));
    }

    public static Optional<Refusal> refusal(OpenAIServiceException e) {
        return Refusal.of(name -> e.headers().values(name), e.statusCode(), e.getMessage());
    }
}
