package io.github.jamalakhras.tollgate.server.admin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class TestHttp {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private TestHttp() {}

    static HttpResponse<String> send(String method, int port, String path, String body, String... headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(int port, String path, String... headers) throws IOException, InterruptedException {
        return send("GET", port, path, "", headers);
    }
}
