package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;

class AdminConfigurationTest {

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65_536})
    void rejectsPortsOutOfRange(int port) {
        assertThatThrownBy(() -> new AdminProperties(port, "127.0.0.1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tollgate.admin.port");
    }

    @Test
    void rejectsABlankAddress() {
        assertThatThrownBy(() -> new AdminProperties(8081, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tollgate.admin.address");
    }

    @Test
    void acceptsATokenOfAtLeast32CharactersAndTreatsABlankOneAsNone() {
        assertThat(new AdminProperties(8081, "127.0.0.1", "t".repeat(32)).token())
                .hasSize(32);
        assertThat(new AdminProperties(8081, "127.0.0.1", "  ").token()).isNull();
        assertThatThrownBy(() -> new AdminProperties(8081, "127.0.0.1", "t".repeat(31)))
                .hasMessage("TOLLGATE_ADMIN_TOKEN must be at least 32 characters");
    }

    @Test
    void refusesToShareTheGatewayPort() {
        var customizer = new AdminPortConfiguration().adminConnector(new AdminProperties(8080, "127.0.0.1", null));
        assertThatThrownBy(() -> customizer.customize(new TomcatServletWebServerFactory(8080)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void addsTheAdminConnectorOnTheConfiguredAddress() {
        var factory = new TomcatServletWebServerFactory(8080);
        new AdminPortConfiguration()
                .adminConnector(new AdminProperties(8081, "127.0.0.1", null))
                .customize(factory);
        assertThat(factory.getAdditionalConnectors()).singleElement().satisfies(connector -> {
            assertThat(connector.getPort()).isEqualTo(8081);
            assertThat(connector.getProperty("address")).hasToString("/127.0.0.1");
        });
    }

    @Test
    void reportsTheBuildVersion() {
        Properties build = new Properties();
        build.setProperty("version", "1.2.3");
        var controller = new AdminInfoController(Optional.of(new BuildProperties(build)));
        assertThat(controller.info().version()).isEqualTo("1.2.3");
    }

    @Test
    void reportsUnknownVersionWithoutBuildMetadata() {
        assertThat(new AdminInfoController(Optional.empty()).info().version()).isEqualTo("unknown");
        var withoutVersion = new AdminInfoController(Optional.of(new BuildProperties(new Properties())));
        assertThat(withoutVersion.info().version()).isEqualTo("unknown");
    }
}
