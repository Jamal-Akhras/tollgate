package io.github.jamalakhras.tollgate.client;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

final class StubServer implements AutoCloseable {

    private final HttpServer server;
    private int status = 200;
    private String body = "{}";
    private final Map<String, String> responseHeaders = new LinkedHashMap<>();
    private volatile String path = "";
    private volatile Headers requestHeaders = new Headers();
    private final AtomicInteger requests = new AtomicInteger();

    StubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            path = exchange.getRequestURI().getPath();
            requestHeaders = exchange.getRequestHeaders();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            responseHeaders.forEach(
                    (name, value) -> exchange.getResponseHeaders().add(name, value));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    StubServer answer(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    StubServer header(String name, String value) {
        responseHeaders.put(name, value);
        return this;
    }

    String path() {
        return path;
    }

    int requests() {
        return requests.get();
    }

    String requestHeader(String name) {
        return requestHeaders.getFirst(name);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
