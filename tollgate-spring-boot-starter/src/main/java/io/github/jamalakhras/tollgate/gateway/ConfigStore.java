package io.github.jamalakhras.tollgate.gateway;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

interface ConfigStore {

    @SuppressWarnings("ArrayRecordComponent")
    record Head(String version, byte[] bytes) {}

    Head head() throws IOException;

    boolean replace(String expected, String next, byte[] bytes) throws IOException;

    void keep(Head head) throws IOException;

    Optional<byte[]> version(String version) throws IOException;

    List<String> versions() throws IOException;
}
