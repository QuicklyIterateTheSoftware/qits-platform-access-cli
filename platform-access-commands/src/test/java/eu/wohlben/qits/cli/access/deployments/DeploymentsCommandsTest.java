package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits deployments settings …` against a fake edge and a fake idp (epic qits-765, qits-1390). */
class DeploymentsCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final String SETTINGS = "/deployments/api/settings";
    private static final String TOKEN_DOOR = SETTINGS + "/qits-ci/dev/API_TOKEN";
    private static final String SECRET = "s3cret-value-never-printed";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_DEPLOYMENTS_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", SETTINGS, """
                {"settings":[{"application":"qits-ci","environment":"dev","key":"API_TOKEN","value":null,"secret":true,
                  "updatedAt":"2026-10-30T09:00:00Z","updatedBy":"admin"},
                 {"application":"qits-ci","environment":"dev","key":"WEBHOOK_URL","value":"https://hooks.example.org",
                  "secret":false,"updatedAt":"2026-10-30T09:01:00Z","updatedBy":"ops"}]}
                """);
        platform.answer("PUT", TOKEN_DOOR, 200, """
                {"application":"qits-ci","environment":"dev","key":"API_TOKEN","value":null,"secret":true,
                 "updatedAt":"2026-10-30T10:00:00Z","updatedBy":"admin"}
                """);
        platform.answer("DELETE", TOKEN_DOOR, 204, "");
        platform.answer("GET", SETTINGS + "/preview/qits-ci/dev", """
                {"application":"qits-ci","environment":"dev",
                 "added":[{"key":"NEW_FLAG","value":"on","secret":false},{"key":"NEW_TOKEN","value":null,"secret":true}],
                 "removed":[{"key":"OLD_FLAG","value":"off","secret":false}],
                 "changed":[{"key":"WEBHOOK_URL","from":"https://a.example.org","to":"https://b.example.org","secret":false},
                            {"key":"API_TOKEN","from":null,"to":null,"secret":true}],
                 "unchanged":3}
                """);
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(InputStream stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), stdin,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result run(String... args) {
        return run(InputStream.nullInputStream(), args);
    }

    private static InputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void listMasksASecretAndShowsAPlainValue() {
        Result r = run("deployments", "settings", "list", "--application", "qits-ci", "--environment", "dev");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("APPLICATION").contains("ENVIRONMENT").contains("KEY").contains("VALUE")
                .contains("UPDATED BY").contains("API_TOKEN").contains("********").contains("WEBHOOK_URL")
                .contains("https://hooks.example.org").contains("admin").contains("ops");
        assertThat(r.out().lines().filter(l -> l.contains("API_TOKEN")).findFirst().orElseThrow()).contains("********");
        assertThat(platform.requests("GET", SETTINGS).getFirst().query()).isEqualTo("application=qits-ci&environment=dev");
    }

    @Test
    void listWithoutAnEnvironmentAsksForEveryOne() {
        Result r = run("deployments", "settings", "list", "--application", "qits-ci", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("\"secret\" : true").contains("\"value\" : null");
        assertThat(platform.requests("GET", SETTINGS).getFirst().query()).isEqualTo("application=qits-ci");
    }

    @Test
    void setReadsTheValueOnStdinAndNeverPrintsIt() throws Exception {
        Result r = run(stdin(SECRET + "\n"), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN", "--secret");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("PUT", TOKEN_DOOR).getFirst();
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(
                "{\"value\":\"" + SECRET + "\",\"secret\":true}"));
        assertThat(sent.contentType()).startsWith("application/json");
        assertThat(r.out()).contains("API_TOKEN").contains("********").doesNotContain(SECRET);
        assertThat(r.err()).doesNotContain(SECRET);
    }

    @Test
    void setTakesOffOneLineBreakOnly() throws Exception {
        Result r = run(stdin("two\n\n"), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("PUT", TOKEN_DOOR).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"value\":\"two\\n\",\"secret\":false}"));
    }

    @Test
    void setNeverRepeatsAPlainValueEither() {
        platform.answer("PUT", TOKEN_DOOR, 200, """
                {"application":"qits-ci","environment":"dev","key":"API_TOKEN","value":"%s","secret":false,
                 "updatedAt":"2026-10-30T10:00:00Z","updatedBy":"admin"}
                """.formatted(SECRET));

        Result table = run(stdin(SECRET), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN");
        Result json = run(stdin(SECRET), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN", "-o", "json");

        assertThat(table.exit()).isZero();
        assertThat(table.out()).contains("********").doesNotContain(SECRET);
        assertThat(json.exit()).isZero();
        assertThat(json.out()).contains("\"value\" : null").doesNotContain(SECRET);
    }

    @Test
    void setRefusesNothingOnStdin() {
        Result empty = run(stdin(""), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN");
        Result newline = run(stdin("\n"), "deployments", "settings", "set", "--application", "qits-ci",
                "--environment", "dev", "--key", "API_TOKEN");

        assertThat(empty.exit()).isEqualTo(2);
        assertThat(empty.err()).contains("Nothing on stdin").contains("Nothing was sent");
        assertThat(newline.exit()).isEqualTo(2);
        assertThat(platform.requests("PUT", TOKEN_DOOR)).isEmpty();
    }

    @Test
    void deletePrintsDeleted() {
        Result r = run("deployments", "settings", "delete", "--application", "qits-ci", "--environment", "dev",
                "--key", "API_TOKEN");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().strip()).isEqualTo("deleted");
        assertThat(platform.requests("DELETE", TOKEN_DOOR)).hasSize(1);
    }

    @Test
    void deleteOfAnUnknownSettingFails() {
        platform.answer("DELETE", TOKEN_DOOR, 404, "{\"message\":\"no setting API_TOKEN\"}");

        Result r = run("deployments", "settings", "delete", "--application", "qits-ci", "--environment", "dev",
                "--key", "API_TOKEN");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("404");
        assertThat(r.out()).doesNotContain("deleted");
    }

    @Test
    void previewListsEachChangeAndTheUnchangedCount() {
        Result r = run("deployments", "settings", "preview", "--application", "qits-ci", "--environment", "dev");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "+ NEW_FLAG=on",
                "+ NEW_TOKEN=********",
                "- OLD_FLAG=off",
                "~ WEBHOOK_URL https://a.example.org -> https://b.example.org",
                "~ API_TOKEN ******** -> ********",
                "3 unchanged.");
        assertThat(platform.requests("GET", SETTINGS + "/preview/qits-ci/dev").getFirst().query()).isNull();
    }

    @Test
    void previewWithExtrasAsksForThemAndListsTheKeptOnes() {
        platform.answer("GET", SETTINGS + "/preview/qits-ci/dev", """
                {"application":"qits-ci","environment":"dev","added":[],"removed":[],"changed":[],
                 "kept":[{"key":"LEGACY_URL","value":"https://old.example.org","secret":false},
                         {"key":"LEGACY_TOKEN","value":null,"secret":true}],
                 "unchanged":1}
                """);

        Result r = run("deployments", "settings", "preview", "--application", "qits-ci", "--environment", "dev",
                "--include-extras");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "= LEGACY_URL=https://old.example.org",
                "= LEGACY_TOKEN=********",
                "1 unchanged.");
        assertThat(platform.requests("GET", SETTINGS + "/preview/qits-ci/dev").getFirst().query())
                .isEqualTo("extras=include");
    }
}
