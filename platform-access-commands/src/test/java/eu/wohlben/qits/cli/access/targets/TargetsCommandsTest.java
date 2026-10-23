package eu.wohlben.qits.cli.access.targets;

import com.fasterxml.jackson.databind.JsonNode;
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

/**
 * {@code qits targets …} as a person types it, against a fake edge (epic qits-1481, ticket
 * qits-1530).
 */
class TargetsCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET = "00000000-0000-4000-8000-000000000030";

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
        env.put("QITS_SYSTEM_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private static String target(String login, String loginCommand) {
        return """
                {"id":"%s","name":"localhost","description":"the build box","slotsTotal":6,
                 "minimums":{"ci":2,"workspace":2,"desk":1},"used":{"ci":1,"workspace":0,"desk":0},
                 "login":{"claude":"%s","kimi":"ABSENT","checkedAt":"2026-10-23T09:00:00Z"},
                 "credentialVolume":"qits_shared_dot_claude","loginCommand":%s,"kimiLoginCommand":null,
                 "connected":true,"connectedSince":"2026-10-23T08:00:00Z","quarantined":false,
                 "quarantineReason":null,"quarantinedAt":null,"runnerVersion":"2026.1023.1",
                 "registered":true,"registeredAt":"2026-10-20T00:00:00Z","lastSeenAt":"2026-10-23T09:59:00Z",
                 "lastHealthCheckAt":"2026-10-23T09:00:00Z","lastHealthCheckOk":true,
                 "createdAt":"2026-10-20T00:00:00Z"}
                """.formatted(TARGET, login, loginCommand == null ? "null" : "\"" + loginCommand + "\"");
    }

    @Test
    void theTargetsAreATableForAPersonAndTheServiceAnswersInJson() throws Exception {
        platform.answer("GET", "/system/api/targets", "[" + target("PRESENT", "docker run --rm -it claude") + "]");

        Result table = run("targets", "list");
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("NAME").contains("SLOTS").contains("LOGIN")
                .containsPattern("localhost\\s+yes\\s+2026.1023.1");
        assertThat(table.out()).contains("ci 1/2, ws 0/2, desk 0/1, total 1/6");
        assertThat(table.out()).contains("claude:PRESENT kimi:ABSENT");

        Result json = run("targets", "list", "-o", "json");
        assertThat(JSON.readTree(json.out()).get(0).path("name").asText()).isEqualTo("localhost");
    }

    @Test
    void listWithNoTargetsSaysSo() {
        platform.answer("GET", "/system/api/targets", "[]");
        Result empty = run("targets", "list");
        assertThat(empty.exit()).as(empty.err()).isZero();
        assertThat(empty.out()).contains("No targets.");
    }

    @Test
    void showResolvesANameAndPrintsEveryField() {
        platform.answer("GET", "/system/api/targets", "[" + target("PRESENT", "docker run --rm -it claude") + "]");
        platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", "docker run --rm -it claude"));

        Result show = run("targets", "show", "--target", "localhost");

        assertThat(show.exit()).as(show.err()).isZero();
        assertThat(show.out()).contains("credential volume").contains("qits_shared_dot_claude")
                .contains("login command").contains("docker run --rm -it claude")
                .contains("login claude").contains("PRESENT");
        assertThat(platform.requests("GET", "/system/api/targets/" + TARGET)).isNotEmpty();
    }

    @Test
    void showByIdSkipsTheListLookup() throws Exception {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));

        Result show = run("targets", "show", "--target", TARGET, "-o", "json");

        assertThat(show.exit()).as(show.err()).isZero();
        assertThat(JSON.readTree(show.out()).path("name").asText()).isEqualTo("localhost");
        assertThat(platform.requests("GET", "/system/api/targets")).isEmpty();
    }

    @Test
    void anUnknownNameIsRefused() {
        platform.answer("GET", "/system/api/targets", "[]");
        Result show = run("targets", "show", "--target", "nowhere");
        assertThat(show.exit()).isEqualTo(2);
        assertThat(show.err()).contains("No target named 'nowhere'");
    }

    @Test
    void updateSendsOnlyWhatIsGiven() throws Exception {
        platform.answer("PATCH", "/system/api/targets/" + TARGET, target("PRESENT", null));

        Result updated = run("targets", "update", "--target", TARGET, "--min-ci", "3", "--description", "new text");

        assertThat(updated.exit()).as(updated.err()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("PATCH", "/system/api/targets/" + TARGET).getFirst().body());
        assertThat(sent.path("description").asText()).isEqualTo("new text");
        assertThat(sent.path("minimums").path("ci").asInt()).isEqualTo(3);
        assertThat(sent.path("minimums").has("workspace")).isFalse();
    }

    @Test
    void updateWithNothingGivenIsRefusedBeforeAnyRequest() {
        Result nothing = run("targets", "update", "--target", TARGET);
        assertThat(nothing.exit()).isEqualTo(2);
        assertThat(nothing.err()).contains("Nothing to change");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void greenlightSaysWhenAStillQuarantinedTargetDidNotClear() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/greenlight",
                target("PRESENT", null).replace("\"quarantined\":false", "\"quarantined\":true"));

        Result greenlit = run("targets", "greenlight", "--target", TARGET);

        assertThat(greenlit.exit()).as(greenlit.err()).isZero();
        assertThat(greenlit.out()).contains("is still quarantined");
    }

    @Test
    void healthcheckIsQueued() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/healthcheck", 202, "{\"requestId\":\"r-1\"}");
        Result queued = run("targets", "healthcheck", "--target", TARGET);
        assertThat(queued.exit()).as(queued.err()).isZero();
        assertThat(queued.out()).contains("Queued a health check on target " + TARGET);
    }

    @Test
    void loginCheckIsSent() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/login-check", 202, "");
        Result sent = run("targets", "login-check", "--target", TARGET);
        assertThat(sent.exit()).as(sent.err()).isZero();
        assertThat(sent.out()).contains("Asked target " + TARGET + " to check its credential volume's sign-in.");
    }

    @Test
    void deleteIsDone() {
        platform.answer("DELETE", "/system/api/targets/" + TARGET, 204, "");
        Result deleted = run("targets", "delete", "--target", TARGET);
        assertThat(deleted.exit()).as(deleted.err()).isZero();
        assertThat(deleted.out()).contains("Deleted target " + TARGET);
    }

    @Test
    void leasesAreATableWithTheFiltersAsQuery() {
        platform.answer("GET", "/system/api/leases", """
                [{"id":"00000000-0000-4000-8000-000000000040","slotType":"WORKSPACE","ownerApp":"qits-workspaces",
                  "ownerRef":"42","targetId":"%s","state":"RUNNING","detail":null,"requestedAt":"2026-10-23T09:00:00Z"}]
                """.formatted(TARGET));

        Result leases = run("targets", "leases", "--type", "workspace", "--state", "RUNNING");

        assertThat(leases.exit()).as(leases.err()).isZero();
        assertThat(leases.out()).contains("WORKSPACE").contains("qits-workspaces/42").contains("RUNNING");
        assertThat(platform.requests("GET", "/system/api/leases").getFirst().query())
                .isEqualTo("slotType=workspace&state=RUNNING");
    }

    @Test
    void signInAlreadyPresentPrintsTheCommandAndExitsAtOnceWithNoLoginCheck() {
        platform.answer("GET", "/system/api/targets/" + TARGET,
                target("PRESENT", "docker run --rm -it --user 1000 --entrypoint claude -v "
                        + "qits_shared_dot_claude:/claude-home image claude"));

        Result signIn = run("targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("Run this on localhost:").contains("docker run --rm -it")
                .contains("is already signed in.");
        assertThat(platform.requests("POST", "/system/api/targets/" + TARGET + "/login-check")).isEmpty();
    }

    @Test
    void signInPollsEvery30SecondsUntilPresent() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", "docker run --rm -it claude"));
        platform.answer("POST", "/system/api/targets/" + TARGET + "/login-check", 202, "");
        time.afterSleep = () -> {
            if (time.sleeps.size() == 2) {
                platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", "docker run --rm -it claude"));
            }
        };

        Result signIn = run("targets", "sign-in", "--target", TARGET, "--timeout", "5m");

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("Run this on localhost:").contains("is signed in.");
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(30), Duration.ofSeconds(30));
        assertThat(platform.requests("POST", "/system/api/targets/" + TARGET + "/login-check")).hasSize(2);
    }

    @Test
    void signInGivesUpAtTheTimeoutNamingTheLastState() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", "docker run --rm -it claude"));
        platform.answer("POST", "/system/api/targets/" + TARGET + "/login-check", 202, "");

        Result signIn = run("targets", "sign-in", "--target", TARGET, "--timeout", "1m");

        assertThat(signIn.exit()).isEqualTo(1);
        assertThat(signIn.err()).contains("still ABSENT after 1m");
        assertThat(time.sleeps).hasSize(2);
    }
}
