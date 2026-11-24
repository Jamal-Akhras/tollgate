package io.github.jamalakhras.tollgate.server.admin;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jamalakhras.tollgate.core.auth.ApiKey;
import io.github.jamalakhras.tollgate.testsupport.TestPorts;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class ConfigEditApiTest {

    @LocalServerPort
    private int port;

    private static final int ADMIN_PORT = TestPorts.free();
    private static final String ADMIN_TOKEN = "a-long-enough-admin-token-for-tests-0004";
    private static final Path FOLDER = temporaryFolder();
    private static final Path CONFIG = FOLDER.resolve("tollgate.yaml");
    private static final Path ORIGINAL = Path.of(CONFIG + ".original");
    private static final ApiKey STARTING_KEY = ApiKey.generate(new SecureRandom());
    private static final String TEXT = handWritten();
    private static final Pattern VERSION = Pattern.compile("\"version\":\"([0-9a-f]{64})\"");
    private static final String GUARD_1 = "/admin/v1/policies/default/guards/1";

    @DynamicPropertySource
    static void server(DynamicPropertyRegistry registry) {
        registry.add("tollgate.config", CONFIG::toString);
        registry.add("tollgate.admin.port", () -> ADMIN_PORT);
        registry.add("tollgate.admin.token", () -> ADMIN_TOKEN);
        registry.add("tollgate.admin.edits", () -> "file");
    }

    private static Path temporaryFolder() {
        try {
            Path folder = Files.createTempDirectory("tollgate-edits");
            folder.toFile().deleteOnExit();
            return folder;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String handWritten() {
        ApiKey key = STARTING_KEY;
        String text = """
                # Written by hand
                dataClasses: [public, internal]
                providers:
                  ollama: { format: openai, baseUrl: "http://localhost:11434/v1", region: local }
                models:
                  qwen3: { provider: ollama, upstream: "qwen3:32b", price: free }
                  metered-model:
                    provider: ollama
                    upstream: "qwen3:32b"
                    price: { input: 1, output: 2, cacheRead: 1, cacheWrite: 1 }
                    maxOutputTokens: { default: 1000, cap: 1000 }
                tenants:
                  support-bot:
                    models: [qwen3]
                    dataClass: internal
                  unbudgeted:
                    models: [metered-model]
                    dataClass: internal
                  metered:
                    models: [metered-model]
                    dataClass: internal
                    budget: { limit: 50, period: monthly }
                keys:
                  - { id: %s, tenant: support-bot, sha256: %s }
                policies:
                  default:
                    guards:
                      - type: request-size
                        maxBytes: 1000000
                      - type: data-class
                        regions: { public: [local], internal: [local] }
                audit: { mode: off }
                """.formatted(key.id(), key.sha256());
        try {
            Files.writeString(CONFIG, text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return text;
    }

    @AfterEach
    void putTheHandWrittenFileBack() throws Exception {
        Files.writeString(CONFIG, TEXT);
        Files.deleteIfExists(ORIGINAL);
        send("POST", "/admin/v1/config/reload");
    }

    private static HttpResponse<String> send(String method, String path) throws IOException, InterruptedException {
        return call(ADMIN_PORT, method, path, "", "Authorization", "Bearer " + ADMIN_TOKEN);
    }

    private static HttpResponse<String> call(int port, String method, String path, String body, String... headers)
            throws IOException, InterruptedException {
        return TestHttp.send(method, port, path, body, headers);
    }

    private static String version() throws IOException, InterruptedException {
        Matcher matcher = VERSION.matcher(send("GET", "/admin/v1/config").body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static HttpResponse<String> put(String path, String body, String ifMatch)
            throws IOException, InterruptedException {
        return call(
                ADMIN_PORT,
                "PUT",
                path,
                body,
                "Authorization",
                "Bearer " + ADMIN_TOKEN,
                "Content-Type",
                "application/json",
                "If-Match",
                ifMatch);
    }

    private static HttpResponse<String> putBudget(String tenant, String limit, String period)
            throws IOException, InterruptedException {
        return put(
                "/admin/v1/tenants/" + tenant + "/budget",
                "{\"limit\":\"" + limit + "\",\"period\":\"" + period + "\"}",
                version());
    }

    private static HttpResponse<String> putMode(String mode) throws IOException, InterruptedException {
        return put(GUARD_1 + "/mode", "{\"mode\":\"" + mode + "\"}", "\"" + version() + "\"");
    }

    private static long versionsKept() throws IOException {
        try (Stream<Path> files = Files.list(FOLDER.resolve("config-history"))) {
            return files.count();
        }
    }

    @Test
    void showsThatTheConfigIsManagedAndNotYetRewritten() throws Exception {
        HttpResponse<String> response = send("GET", "/admin/v1/config");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"edits\":\"managed\",\"rewritten\":false");
    }

    @Test
    void switchesAGuardToMonitorAndBack() throws Exception {
        String started = version();

        HttpResponse<String> monitored = putMode("monitor");

        assertThat(monitored.statusCode()).isEqualTo(200);
        assertThat(monitored.body())
                .contains("\"previous\":\"" + started + "\"")
                .contains("\"changed\":true")
                .doesNotContain("\"version\":\"" + started + "\"");
        HttpResponse<String> view = send("GET", "/admin/v1/config");
        assertThat(view.body()).contains("\"mode\":\"monitor\"").contains("\"rewritten\":true");
        assertThat(view.body()).contains("\"version\":\"" + version() + "\"");
        assertThat(Files.readString(CONFIG)).contains("mode: \"monitor\"");
        assertThat(Files.readString(ORIGINAL)).contains("# Written by hand");

        HttpResponse<String> enforced = putMode("enforce");

        assertThat(enforced.body()).contains("\"changed\":true");
        assertThat(Files.readString(CONFIG)).doesNotContain("monitor");
        assertThat(putMode("enforce").body()).contains("\"changed\":false");
    }

    @Test
    void answersWithTheSameVersionWhenTheModeIsAlreadySet() throws Exception {
        String started = version();

        HttpResponse<String> response = putMode("enforce");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"version\":\"" + started + "\"").contains("\"changed\":false");
        assertThat(Files.exists(ORIGINAL)).isFalse();
    }

    @Test
    void asksForTheConfigVersion() throws Exception {
        HttpResponse<String> response = call(
                ADMIN_PORT,
                "PUT",
                GUARD_1 + "/mode",
                "{\"mode\":\"monitor\"}",
                "Authorization",
                "Bearer " + ADMIN_TOKEN,
                "Content-Type",
                "application/json");

        assertThat(response.statusCode()).isEqualTo(428);
        assertThat(response.body()).contains("\"code\":\"precondition_required\"");
    }

    @Test
    void refusesAStaleVersion() throws Exception {
        HttpResponse<String> response = put(GUARD_1 + "/mode", "{\"mode\":\"monitor\"}", "\"" + "0".repeat(64) + "\"");

        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(response.body()).contains("\"code\":\"config_changed\"");
        assertThat(Files.exists(ORIGINAL)).isFalse();
    }

    @Test
    void refusesAVersionThatIsNotAVersion() throws Exception {
        for (String sent : List.of("*", "\"abc\"", "W/\"abc\"", "x", "W/\"" + "0".repeat(64) + "\"")) {
            HttpResponse<String> response = put(GUARD_1 + "/mode", "{\"mode\":\"monitor\"}", sent);

            assertThat(response.statusCode()).as(sent).isEqualTo(412);
            assertThat(response.body()).contains("\"code\":\"config_changed\"");
        }
        assertThat(Files.exists(ORIGINAL)).isFalse();
    }

    @Test
    void refusesAFileChangedByHandAndNotReloaded() throws Exception {
        String started = version();
        Files.writeString(CONFIG, TEXT + "# changed by hand\n");

        HttpResponse<String> response = put(GUARD_1 + "/mode", "{\"mode\":\"monitor\"}", started);

        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(Files.readString(CONFIG)).isEqualTo(TEXT + "# changed by hand\n");
    }

    @Test
    void acceptsAVersionWithoutQuotes() throws Exception {
        HttpResponse<String> response = put(GUARD_1 + "/mode", "{\"mode\":\"enforce\"}", version());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void rejectsWhatItCannotChange() throws Exception {
        assertThat(put("/admin/v1/policies/nope/guards/0/mode", "{\"mode\":\"monitor\"}", version())
                        .body())
                .contains("\"code\":\"not_found\"")
                .contains("No policy named 'nope'");
        HttpResponse<String> missing =
                put("/admin/v1/policies/default/guards/9/mode", "{\"mode\":\"monitor\"}", version());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("Policy 'default' has no guard 9");
        HttpResponse<String> index =
                put("/admin/v1/policies/default/guards/x/mode", "{\"mode\":\"monitor\"}", version());
        assertThat(index.statusCode()).isEqualTo(400);
        assertThat(index.body()).contains("index must be a number");
        HttpResponse<String> off = putMode("off");
        assertThat(off.statusCode()).isEqualTo(400);
        assertThat(off.body()).contains("mode must be enforce or monitor, was off");
        HttpResponse<String> text = put(GUARD_1 + "/mode", "monitor", version());
        assertThat(text.statusCode()).isEqualTo(400);
        assertThat(text.body()).contains("The request body is not valid");
    }

    @Test
    void setsABudgetOnATenantThatHadNone() throws Exception {
        HttpResponse<String> response = putBudget("unbudgeted", "20", "daily");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"changed\":true");
        HttpResponse<String> budgets = send("GET", "/admin/v1/budgets");
        assertThat(budgets.body())
                .contains("\"tenant\":\"unbudgeted\",\"period\":\"daily\",\"currency\":\"USD\",\"limit\":\"20\","
                        + "\"spent\":\"0\"");
    }

    @Test
    void explainsWhyATenantOfFreeModelsCannotHaveABudget() throws Exception {
        HttpResponse<String> response = putBudget("support-bot", "20", "daily");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"code\":\"invalid_config\"").contains("tenants.support-bot.budget");
        assertThat(Files.exists(ORIGINAL)).isFalse();
    }

    @Test
    void changesTheLimitOfAnExistingBudget() throws Exception {
        HttpResponse<String> response = putBudget("metered", "20", "monthly");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(send("GET", "/admin/v1/budgets").body())
                .contains("\"tenant\":\"metered\",\"period\":\"monthly\",\"currency\":\"USD\",\"limit\":\"20\"");
        assertThat(send("GET", "/admin/v1/config").body()).contains("\"budget\":{\"limit\":\"20\"");
    }

    @Test
    void rejectsABudgetItCannotSet() throws Exception {
        assertThat(putBudget("nobody", "20", "daily").statusCode()).isEqualTo(404);
        assertThat(putBudget("metered", "abc", "daily").statusCode()).isEqualTo(400);
        assertThat(putBudget("metered", "-1", "daily").statusCode()).isEqualTo(400);
        HttpResponse<String> period = putBudget("metered", "1", "weekly");
        assertThat(period.statusCode()).isEqualTo(400);
        assertThat(period.body()).contains("period must be one of daily, monthly, total, was weekly");
    }

    @Test
    void refusesALimitWithTooManyDecimalPlaces() throws Exception {
        HttpResponse<String> response = putBudget("metered", "0.0000000001", "daily");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("has more than 9 decimal places");
    }

    @Test
    void refusesALimitThatCannotBeCounted() throws Exception {
        for (String limit : List.of("1e999999999", "1e-999999999", "9223372037", "99999999999")) {
            HttpResponse<String> response = putBudget("metered", limit, "daily");

            assertThat(response.statusCode()).as(limit).isEqualTo(400);
        }
        assertThat(Files.exists(ORIGINAL)).isFalse();
    }

    private static final Pattern KEY = Pattern.compile("\"key\":\"(tg_[^\"]+)\"");

    private HttpResponse<String> change(String method, String path, String ifMatch)
            throws IOException, InterruptedException {
        return call(
                ADMIN_PORT,
                method,
                path,
                "",
                "Authorization",
                "Bearer " + ADMIN_TOKEN,
                "If-Match",
                "\"" + ifMatch + "\"");
    }

    private int models(String key) throws IOException, InterruptedException {
        return call(port, "GET", "/v1/models", "", "Authorization", "Bearer " + key)
                .statusCode();
    }

    private static String keyOf(HttpResponse<String> created) {
        Matcher matcher = KEY.matcher(created.body());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private static String everythingOnDisk() throws IOException {
        StringBuilder text = new StringBuilder(Files.readString(CONFIG));
        try (Stream<Path> files = Files.list(FOLDER.resolve("config-history"))) {
            for (Path file : files.toList()) {
                text.append(Files.readString(file));
            }
        }
        return text.toString();
    }

    @Test
    void createsAKeyThatWorksAndIsShownOnce(CapturedOutput output) throws Exception {
        String started = version();

        HttpResponse<String> created = change("POST", "/admin/v1/tenants/support-bot/keys", started);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.headers().firstValue("Cache-Control"))
                .hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        String key = keyOf(created);
        ApiKey parsed = ApiKey.parse(key).orElseThrow();
        assertThat(created.body())
                .contains("\"id\":\"" + parsed.id() + "\"")
                .contains("\"tenant\":\"support-bot\"")
                .contains("\"previous\":\"" + started + "\"")
                .doesNotContain("\"version\":\"" + started + "\"");
        assertThat(models(key)).isEqualTo(200);
        String disk = everythingOnDisk();
        assertThat(disk).contains(parsed.id()).contains(parsed.sha256()).doesNotContain(key);
        assertThat(send("GET", "/admin/v1/config").body())
                .contains("{\"id\":\"" + parsed.id() + "\",\"tenant\":\"support-bot\"}")
                .doesNotContain(key);
        assertThat(output.getAll()).contains(parsed.id()).doesNotContain(key).doesNotContain(parsed.secret());
    }

    @Test
    void createsADifferentKeyEachTime() throws Exception {
        HttpResponse<String> first = change("POST", "/admin/v1/tenants/support-bot/keys", version());
        HttpResponse<String> second = change("POST", "/admin/v1/tenants/support-bot/keys", version());

        String one = keyOf(first);
        String two = keyOf(second);
        assertThat(ApiKey.parse(one).orElseThrow().id())
                .isNotEqualTo(ApiKey.parse(two).orElseThrow().id());
        assertThat(models(one)).isEqualTo(200);
        assertThat(models(two)).isEqualTo(200);
    }

    @Test
    void doesNotCreateAKeyForAnUnknownTenant() throws Exception {
        String before = Files.readString(CONFIG);

        HttpResponse<String> response = change("POST", "/admin/v1/tenants/nobody/keys", version());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("No tenant named 'nobody'").doesNotContain("tg_");
        assertThat(Files.readString(CONFIG)).isEqualTo(before);
    }

    @Test
    void doesNotShowAKeyWhenTheVersionIsStale() throws Exception {
        HttpResponse<String> response = change("POST", "/admin/v1/tenants/support-bot/keys", "0".repeat(64));

        assertThat(response.statusCode()).isEqualTo(412);
        assertThat(response.body()).doesNotContain("tg_");
    }

    @Test
    void revokesAKeyAndTheGatewayRefusesItAtOnce() throws Exception {
        String key = keyOf(change("POST", "/admin/v1/tenants/support-bot/keys", version()));
        String other = keyOf(change("POST", "/admin/v1/tenants/support-bot/keys", version()));
        String id = ApiKey.parse(key).orElseThrow().id();
        assertThat(models(key)).isEqualTo(200);

        HttpResponse<String> revoked = change("DELETE", "/admin/v1/keys/" + id, version());

        assertThat(revoked.statusCode()).isEqualTo(200);
        assertThat(revoked.body()).contains("\"changed\":true");
        assertThat(models(key)).isEqualTo(401);
        assertThat(models(other)).isEqualTo(200);
        assertThat(Files.readString(CONFIG)).doesNotContain(id);
    }

    @Test
    void revokesATenantsLastKeyAndKeepsTheTenant() throws Exception {
        assertThat(models(STARTING_KEY.value())).isEqualTo(200);

        HttpResponse<String> revoked = change("DELETE", "/admin/v1/keys/" + STARTING_KEY.id(), version());

        assertThat(revoked.statusCode()).isEqualTo(200);
        String view = send("GET", "/admin/v1/config").body();
        assertThat(view).contains("\"name\":\"support-bot\"").doesNotContain(STARTING_KEY.id());
        assertThat(models(STARTING_KEY.value())).isEqualTo(401);
    }

    @Test
    void doesNotRevokeAnUnknownKeyAndChecksTheVersion() throws Exception {
        HttpResponse<String> missing = change("DELETE", "/admin/v1/keys/nope", version());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).contains("No key with the ID nope");

        HttpResponse<String> stale = change("DELETE", "/admin/v1/keys/nope", "0".repeat(64));
        assertThat(stale.statusCode()).isEqualTo(412);
    }

    @Test
    void needsTheAdminTokenAndTheAdminPort() throws Exception {
        assertThat(call(ADMIN_PORT, "PUT", GUARD_1 + "/mode", "{\"mode\":\"monitor\"}", "If-Match", version())
                        .statusCode())
                .isEqualTo(401);
        assertThat(call(
                                port,
                                "PUT",
                                GUARD_1 + "/mode",
                                "{\"mode\":\"monitor\"}",
                                "Authorization",
                                "Bearer " + ADMIN_TOKEN,
                                "If-Match",
                                version())
                        .statusCode())
                .isEqualTo(404);
    }

    @Test
    void keepsTheVersionItStartedWithAndEachVersionAReloadBrings() throws Exception {
        long before = versionsKept();
        assertThat(before).isPositive();

        Files.writeString(CONFIG, TEXT + "# changed by hand\n");
        HttpResponse<String> reloaded = send("POST", "/admin/v1/config/reload");

        assertThat(reloaded.statusCode()).isEqualTo(200);
        assertThat(reloaded.body()).contains("\"changed\":true");
        assertThat(versionsKept()).isEqualTo(before + 1);
    }
}
