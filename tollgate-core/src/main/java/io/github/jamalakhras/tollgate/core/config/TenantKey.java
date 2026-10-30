package io.github.jamalakhras.tollgate.core.config;

import java.util.HexFormat;

public record TenantKey(String id, String tenant, String sha256) {

    public TenantKey {
        if (!id.matches("[0-9A-Za-z]{12}")) {
            throw new IllegalArgumentException("id must be the 12-character key ID printed by 'tollgate keys create'");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
    }

    public byte[] digest() {
        return HexFormat.of().parseHex(sha256);
    }
}
