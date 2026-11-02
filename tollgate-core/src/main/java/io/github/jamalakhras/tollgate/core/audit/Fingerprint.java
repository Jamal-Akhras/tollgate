package io.github.jamalakhras.tollgate.core.audit;

import javax.crypto.Mac;

public final class Fingerprint {

    private final Mac mac;

    Fingerprint(Mac mac) {
        this.mac = mac;
    }

    public void add(byte[] bytes) {
        mac.update(bytes);
    }

    public String hex() {
        return Digests.HEX.formatHex(mac.doFinal());
    }
}
