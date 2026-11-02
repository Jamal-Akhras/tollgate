package io.github.jamalakhras.tollgate.server.gateway;

import java.io.IOException;
import java.util.OptionalLong;

interface StreamTarget {

    void open(OptionalLong outputLimitedTo) throws IOException;

    void write(byte[] event) throws IOException;
}
