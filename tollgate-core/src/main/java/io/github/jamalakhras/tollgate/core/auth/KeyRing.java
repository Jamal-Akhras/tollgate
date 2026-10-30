package io.github.jamalakhras.tollgate.core.auth;

import io.github.jamalakhras.tollgate.core.config.TenantKey;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class KeyRing {

    private final Map<String, TenantKey> keysById;

    public KeyRing(Collection<TenantKey> keys) {
        this.keysById = keys.stream().collect(Collectors.toUnmodifiableMap(TenantKey::id, Function.identity()));
    }

    public Optional<String> tenantOf(String presentedKey) {
        return ApiKey.parse(presentedKey)
                .flatMap(key -> Optional.ofNullable(keysById.get(key.id()))
                        .filter(stored -> MessageDigest.isEqual(stored.digest(), key.digest()))
                        .map(TenantKey::tenant));
    }
}
