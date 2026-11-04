package io.github.jamalakhras.tollgate.server.cli;

import io.github.jamalakhras.tollgate.core.audit.AuditKeys;
import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;

final class Init {

    static final String CONFIG = "tollgate.yaml";
    static final String SECRETS = "tollgate.env";
    static final String TENANT = "my-app";

    private static final int ADMIN_TOKEN_BYTES = 32;

    private Init() {}

    static Created create(Path directory, SecureRandom random) throws IOException {
        Path config = directory.resolve(CONFIG);
        Path secrets = directory.resolve(SECRETS);
        for (Path file : List.of(config, secrets)) {
            if (Files.exists(file)) {
                throw new FileAlreadyExistsException(file.toString());
            }
        }
        Files.createDirectories(directory);
        AuditKeys.NewKeys auditKeys = AuditKeys.generate(random);
        ApiKey key = ApiKey.generate(random);
        byte[] adminToken = new byte[ADMIN_TOKEN_BYTES];
        random.nextBytes(adminToken);
        writePrivately(secrets, """
                TOLLGATE_AUDIT_SIGNING_KEY=%s
                TOLLGATE_AUDIT_HMAC_KEY=%s
                TOLLGATE_ADMIN_TOKEN=%s
                """.formatted(
                auditKeys.signingKey(), auditKeys.hmacKey(), HexFormat.of().formatHex(adminToken)));
        Files.writeString(
                config,
                """
                dataClasses: [public, internal, confidential]

                providers:
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }

                models:
                  qwen3:
                    provider: ollama
                    upstream: "qwen3:8b"
                    price: free
                    maxOutputTokens: { default: 1024, cap: 4096 }

                tenants:
                  %1$s:
                    models: [qwen3]
                    dataClass: internal

                keys:
                  - { id: %2$s, tenant: %1$s, sha256: %3$s }

                policies:
                  default:
                    guards:
                      - { type: request-size, maxBytes: 1048576 }
                      - type: data-class
                        regions: { public: [local], internal: [local], confidential: [local] }
                    features:
                      unreviewed-fields: [public, internal]
                      remote-images: [public, internal]
                    redact:
                      card: [public, internal, confidential]
                    refuse:
                      secret: [public, internal, confidential]

                audit:
                  mode: sync
                  directory: audit
                  publicKey: %4$s
                  signingKey: ${env:TOLLGATE_AUDIT_SIGNING_KEY}
                  hmacKey: ${env:TOLLGATE_AUDIT_HMAC_KEY}
                """.formatted(TENANT, key.id(), key.sha256(), auditKeys.publicKey()),
                StandardOpenOption.CREATE_NEW);
        return new Created(config, secrets, key, auditKeys.publicKey());
    }

    private static void writePrivately(Path file, String content) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
            if (acl != null) {
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(acl.getOwner())
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()));
            }
        }
        Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
    }

    record Created(Path config, Path secrets, ApiKey key, String publicKey) {}
}
