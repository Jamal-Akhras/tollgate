package io.github.jamalakhras.tollgate.server.gateway;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;

final class ServletStream implements StreamTarget {

    private final HttpServletResponse response;

    ServletStream(HttpServletResponse response) {
        this.response = response;
    }

    @Override
    public void open(Map<String, String> headers) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        headers.forEach(response::setHeader);
        response.flushBuffer();
    }

    @Override
    public void write(byte[] event) throws IOException {
        response.getOutputStream().write(event);
        response.flushBuffer();
    }
}
