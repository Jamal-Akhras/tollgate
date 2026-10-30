package io.github.jamalakhras.tollgate.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

public final class TestPorts {

    private TestPorts() {}

    public static int free() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
