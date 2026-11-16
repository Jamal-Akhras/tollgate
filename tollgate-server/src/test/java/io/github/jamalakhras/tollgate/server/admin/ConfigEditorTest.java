package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jamalakhras.tollgate.gateway.StartupException;
import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigEditorTest {

    @TempDir
    Path folder;

    @Test
    void acceptsAFileItCanRewrite() throws Exception {
        Path config = Files.writeString(folder.resolve("tollgate.yaml"), "tenants: {}\n");

        assertThatCode(() -> ConfigEditor.requireWritable(config)).doesNotThrowAnyException();
    }

    @Test
    void refusesAReadOnlyFile() throws Exception {
        Path config = Files.writeString(folder.resolve("tollgate.yaml"), "tenants: {}\n");
        File file = config.toFile();
        assertThat(file.setWritable(false)).isTrue();
        try {
            assertThatThrownBy(() -> ConfigEditor.requireWritable(config))
                    .isInstanceOf(StartupException.class)
                    .hasMessageContaining(config.toString())
                    .hasMessageContaining("the file is not writable")
                    .hasMessageContaining("TOLLGATE_ADMIN_EDITS");
        } finally {
            file.setWritable(true);
        }
    }

    @Test
    void refusesAFolderItCannotWriteTo() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"));
        Path locked = Files.createDirectory(folder.resolve("locked"));
        Path config = Files.writeString(locked.resolve("tollgate.yaml"), "tenants: {}\n");
        assertThat(locked.toFile().setWritable(false)).isTrue();
        try {
            assumeTrue(!Files.isWritable(locked));
            assertThatThrownBy(() -> ConfigEditor.requireWritable(config))
                    .isInstanceOf(StartupException.class)
                    .hasMessageContaining("the folder is not writable")
                    .hasMessageContaining("TOLLGATE_ADMIN_EDITS");
        } finally {
            locked.toFile().setWritable(true);
        }
    }

    @Test
    void describesAManagedWriteInOneLine() {
        var write = new ConfigEditor.ManagedWrite(
                "5d9c1b52-0f0e-4a1b-9d55-6f5c3d1c2b7a",
                AdminCaller.ADMIN,
                "aaa",
                "bbb",
                List.of(
                        new ConfigEditor.Operation("set-budget", "tenants.metered.budget"),
                        new ConfigEditor.Operation("create-key", "keys")));

        assertThat(write.line())
                .isEqualTo("Config change 5d9c1b52-0f0e-4a1b-9d55-6f5c3d1c2b7a by ADMIN: version bbb replaces aaa;"
                        + " set-budget tenants.metered.budget, create-key keys");
    }
}
