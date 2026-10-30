package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "tollgate.admin.port=" + AdminApiSpecTest.ADMIN_PORT)
@AutoConfigureMockMvc
class AdminApiSpecTest {

    static final int ADMIN_PORT = 18_081;
    private static final Path PUBLISHED_SPEC = Path.of("..", "docs", "api", "admin-openapi.json");

    @Autowired
    private MockMvc mvc;

    @Test
    void publishedContractMatchesTheCode() throws Exception {
        String generated = mvc.perform(
                        get("/v3/api-docs/" + AdminApiDocsConfiguration.GROUP).with(request -> {
                            request.setLocalPort(ADMIN_PORT);
                            return request;
                        }))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8)
                .strip()
                .replace("\r\n", "\n");

        if (Boolean.getBoolean("tollgate.updateApiSpec")) {
            Files.writeString(PUBLISHED_SPEC, generated + "\n");
        }

        assertThat(Files.readString(PUBLISHED_SPEC).strip().replace("\r\n", "\n"))
                .as("docs/api/admin-openapi.json is out of date; rerun with -Dtollgate.updateApiSpec=true")
                .isEqualTo(generated);
    }
}
