package io.github.jamalakhras.tollgate.providers;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {

    private final long limit;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.@Nullable Subscription subscription;

    private LimitedBody(long limit) {
        this.limit = limit;
    }

    static HttpResponse.BodyHandler<byte[]> handler(long limit) {
        return info -> new LimitedBody(limit);
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            if (bytes.size() + (long) item.remaining() > limit) {
                Flow.Subscription current = subscription;
                if (current != null) {
                    current.cancel();
                }
                body.completeExceptionally(new InvalidProviderResponseException(
                        "The provider's response was larger than " + limit + " bytes"));
                return;
            }
            byte[] chunk = new byte[item.remaining()];
            item.get(chunk);
            bytes.writeBytes(chunk);
        }
    }

    @Override
    public void onError(Throwable throwable) {
        body.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
        body.complete(bytes.toByteArray());
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }
}
