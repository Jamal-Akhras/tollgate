package io.github.jamalakhras.tollgate.providers;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

public final class FakeProvider implements AutoCloseable {

    public record Received(String method, String path, Map<String, List<String>> headers, String body) {}

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile Duration delay = Duration.ZERO;
    private volatile Duration drip = Duration.ZERO;

    public FakeProvider() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void respond(int status, String body) {
        this.status = status;
        this.responseBody = body;
    }

    public void delay(Duration delay) {
        this.delay = delay;
    }

    public void drip(Duration perByte) {
        this.drip = perByte;
    }

    public void reset() {
        received.clear();
        respond(200, "{}");
        delay(Duration.ZERO);
        drip(Duration.ZERO);
    }

    public List<Received> received() {
        return received;
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(new Received(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                Map.copyOf(exchange.getRequestHeaders()),
                body));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Retry-After", "7");
        exchange.sendResponseHeaders(status, bytes.length);
        if (drip.isZero()) {
            exchange.getResponseBody().write(bytes);
        } else {
            dripped(exchange, bytes);
        }
        exchange.close();
    }

    private void dripped(HttpExchange exchange, byte[] bytes) {
        try {
            for (byte b : bytes) {
                exchange.getResponseBody().write(b);
                exchange.getResponseBody().flush();
                Thread.sleep(drip);
            }
        } catch (IOException e) {
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
