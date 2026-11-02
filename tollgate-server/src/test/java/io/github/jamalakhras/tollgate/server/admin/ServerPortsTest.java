package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jamalakhras.tollgate.server.TestPorts;
import java.io.IOException;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ServerPortsTest {

    private static final int ADMIN_PORT = TestPorts.free();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @LocalServerPort
    private int gatewayPort;

    @DynamicPropertySource
    static void adminPort(DynamicPropertyRegistry registry) {
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Test
    void servesTheAdminApiOnTheAdminPort() throws Exception {
        HttpResponse<String> response = get(ADMIN_PORT, "/admin/v1/info");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"service\":\"tollgate\"");
        assertThat(get(ADMIN_PORT, "/v3/api-docs/admin").statusCode()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/admin/v1/info",
                "/%61dmin/v1/info",
                "/admin;x=1/v1/info",
                "/./admin/v1/info",
                "/v3/api-docs",
                "/v3/api-docs.yaml",
                "/v3/api-docs/admin.yaml"
            })
    void hidesAdminRoutesOnTheGatewayPort(String path) throws Exception {
        assertThat(get(gatewayPort, path).statusCode()).isIn(400, 404);
    }

    @Test
    void hidesGatewayRoutesOnTheAdminPort() throws Exception {
        assertThat(get(ADMIN_PORT, "/v1/chat/completions").statusCode()).isEqualTo(404);
    }

    @Test
    void keepsTheAdminPortOffOtherNetworkInterfaces() throws Exception {
        Optional<InetAddress> address = nonLoopbackAddress();
        assumeTrue(address.isPresent(), "no non-loopback IPv4 interface on this machine");

        connect(address.get(), gatewayPort);
        assertThatThrownBy(() -> connect(address.get(), ADMIN_PORT))
                .isInstanceOfAny(ConnectException.class, SocketTimeoutException.class);
    }

    private static void connect(InetAddress address, int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 2_000);
        }
    }

    private static Optional<InetAddress> nonLoopbackAddress() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address)
                .filter(address -> !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                .findFirst();
    }

    private static HttpResponse<String> get(int port, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
