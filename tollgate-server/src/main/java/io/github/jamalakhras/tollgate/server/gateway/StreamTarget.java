package io.github.jamalakhras.tollgate.server.gateway;

import java.io.IOException;
import java.util.Map;

interface StreamTarget {

    void open(Map<String, String> headers) throws IOException;

    void write(byte[] event) throws IOException;
}
