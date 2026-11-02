package io.github.jamalakhras.tollgate.providers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

final class Lines implements HttpResponse.BodySubscriber<Lines> {

    private static final Object END = new Object();

    private final long limit;
    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    private long received;
    private volatile Flow.@Nullable Subscription subscription;

    Lines(long limit) {
        this.limit = limit;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.subscription = subscription;
        subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> items) {
        for (ByteBuffer item : items) {
            received += item.remaining();
            if (received > limit) {
                cancel();
                queue.add(new InvalidProviderResponseException(
                        "The provider's response was larger than " + limit + " bytes"));
                return;
            }
            while (item.hasRemaining()) {
                byte next = item.get();
                if (next == '\n') {
                    queue.add(line());
                } else {
                    partial.write(next);
                }
            }
        }
    }

    @Override
    public void onError(Throwable throwable) {
        queue.add(throwable);
    }

    @Override
    public void onComplete() {
        if (partial.size() > 0) {
            queue.add(line());
        }
        queue.add(END);
    }

    @Override
    public CompletionStage<Lines> getBody() {
        return CompletableFuture.completedFuture(this);
    }

    @Nullable
    String next(Duration wait) throws InterruptedException {
        Object next = queue.poll(wait.toNanos(), TimeUnit.NANOSECONDS);
        if (next == null) {
            throw ProviderClient.timedOut(wait, new IOException("No data within " + wait));
        }
        if (next == END) {
            queue.add(END);
            return null;
        }
        if (next instanceof InvalidProviderResponseException invalid) {
            throw invalid;
        }
        if (next instanceof Throwable failure) {
            throw new ProviderUnavailableException("The provider's stream broke off", false, failure);
        }
        return (String) next;
    }

    void cancel() {
        Flow.Subscription current = subscription;
        if (current != null) {
            current.cancel();
        }
    }

    private String line() {
        String line = partial.toString(StandardCharsets.UTF_8);
        partial.reset();
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
