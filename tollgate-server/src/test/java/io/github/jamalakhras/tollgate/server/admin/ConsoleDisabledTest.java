package io.github.jamalakhras.tollgate.server.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {"tollgate.admin.port=" + ConsoleDisabledTest.ADMIN_PORT, "tollgate.console.enabled=false"})
@AutoConfigureMockMvc
class ConsoleDisabledTest {

    static final int ADMIN_PORT = 18_082;

    @Autowired
    private MockMvc mvc;

    @Test
    void answers404WhenTheConsoleIsOff() throws Exception {
        for (String path : new String[] {"/console/", "/"}) {
            mvc.perform(get(path).with(request -> {
                        request.setLocalPort(ADMIN_PORT);
                        return request;
                    }))
                    .andExpect(status().isNotFound());
        }
    }
}
