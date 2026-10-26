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
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
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

/**
 * {@code qits targets …} as a person types it, against a fake edge (epic qits-1481, tickets
 * qits-1530 and qits-1574).
 */
class TargetsCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-23T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TARGET = "00000000-0000-4000-8000-000000000030";
    private static final String REG_TOKEN = "reg-token-must-only-reach-stdout";
    private static final String INSTALL_LINE =
            "curl -fsSL https://system.qits.example/install.sh | sh -s -- --token " + REG_TOKEN;

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
        return run(InputStream.nullInputStream(), args);
    }

    private Result run(InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), in,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private static InputStream stdin(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
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

    // --- leases show and end (qits-1644) ---

    private static final String LEASE = "00000000-0000-4000-8000-000000000040";

    private static String lease(String state, String detail) {
        return """
                {"id":"%s","slotType":"CI","ownerApp":"qits-ci","ownerRef":"run-7","projectId":null,"workKey":null,
                 "pinTargetId":null,"targetId":"%s","priority":5,"state":"%s","spec":{},"specHash":"abc",
                 "adopt":null,"exitCode":null,"detail":%s,"requestedAt":"2026-10-23T09:00:00Z",
                 "grantedAt":"2026-10-23T09:00:05Z","startedAt":"2026-10-23T09:00:10Z","endedAt":null}
                """.formatted(LEASE, TARGET, state, quoted(detail));
    }

    @Test
    void leasesShowReadsOneLease() throws Exception {
        platform.answer("GET", "/system/api/leases/" + LEASE, lease("RUNNING", null));

        Result shown = run("targets", "leases", "show", LEASE);

        assertThat(shown.exit()).as(shown.err()).isZero();
        assertThat(shown.out()).contains(LEASE).contains("qits-ci/run-7").contains("RUNNING").contains(TARGET);
        assertThat(platform.requests("GET", "/system/api/leases")).isEmpty();

        Result json = run("targets", "-o", "json", "leases", "show", LEASE);
        assertThat(JSON.readTree(json.out()).path("specHash").asText()).isEqualTo("abc");
    }

    @Test
    void theBareLeasesCommandStillLists() {
        platform.answer("GET", "/system/api/leases", "[]");

        Result leases = run("targets", "leases", "--target", TARGET);

        assertThat(leases.exit()).as(leases.err()).isZero();
        assertThat(leases.out()).isEqualTo("No leases.\n");
        assertThat(platform.requests("GET", "/system/api/leases").getFirst().query()).isEqualTo("targetId=" + TARGET);
    }

    @Test
    void leasesEndSendsTheReasonAndPrintsTheLease() {
        platform.answer("DELETE", "/system/api/leases/" + LEASE, 200, lease("ENDED", "ended by someone: runner gone"));

        Result ended = run("targets", "leases", "end", LEASE, "--reason", "runner gone");

        assertThat(ended.exit()).as(ended.err()).isZero();
        FakePlatform.Request delete = platform.requests("DELETE", "/system/api/leases/" + LEASE).getFirst();
        assertThat(delete.query()).isEqualTo("reason=runner%20gone");
        assertThat(ended.out()).startsWith("Lease " + LEASE + " is ENDED.")
                .contains("ended by someone: runner gone");
    }

    @Test
    void leasesEndWithoutAReasonSendsNoQuery() {
        platform.answer("DELETE", "/system/api/leases/" + LEASE, 200, lease("ENDING", null));

        Result ended = run("targets", "leases", "end", LEASE);

        assertThat(ended.exit()).as(ended.err()).isZero();
        assertThat(platform.requests("DELETE", "/system/api/leases/" + LEASE).getFirst().query()).isNullOrEmpty();
        assertThat(ended.out()).startsWith("Lease " + LEASE + " is ENDING.");
    }

    @Test
    void leasesEndRefusedOrWithoutAnIdSaysSo() {
        platform.answer("DELETE", "/system/api/leases/" + LEASE, 403, "{\"message\":\"NOT_YOUR_LEASE\"}");
        Result refused = run("targets", "leases", "end", LEASE);
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("403");

        Result notAnId = run("targets", "leases", "end", "stuck-one");
        assertThat(notAnId.exit()).isEqualTo(2);
        assertThat(notAnId.err()).contains("Name the lease by its id");
        assertThat(platform.requests).noneMatch(r -> r.method().equals("DELETE") && r.path().contains("stuck-one"));
    }

    @Test
    void leasesEndHelpNamesTheOperatorRolesTheAuditAndTheStuckEnding() {
        Result help = run("targets", "leases", "end", "--help");

        assertThat(help.exit()).as(help.err()).isZero();
        String text = help.out().replaceAll("\\s+", " ");
        assertThat(text).contains("qits:admin, qits:admin-agent or qits:desk-admin").contains("audited")
                .contains("A lease already ENDING that an operator ends goes ENDED at once");
    }

    // --- sign-in (qits-1593) ---

    private static final String SIGNIN = "00000000-0000-4000-8000-000000000050";

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private static String signIn(String harness, String state, String url, String userCode, String detail) {
        return """
                {"signInId":"%s","targetId":"%s","harness":"%s","state":"%s","url":%s,"userCode":%s,"detail":%s,
                 "startedAt":"2026-10-23T09:00:00Z","updatedAt":"2026-10-23T09:00:00Z"}
                """.formatted(SIGNIN, TARGET, harness, state, quoted(url), quoted(userCode), quoted(detail));
    }

    private static final String SIGN_INS = "/system/api/targets/" + TARGET + "/sign-ins";
    private static final String SIGN_IN = SIGN_INS + "/" + SIGNIN;

    @Test
    void signInAlreadyPresentExitsAtOnceWithNoSignInStarted() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", null));

        Result signIn = run("targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("is already signed in.");
        assertThat(platform.requests("POST", SIGN_INS)).isEmpty();
    }

    @Test
    void signInClaudeHappyPathPrintsTheUrlReadsTheCodeFromStdinAndSucceeds() throws Exception {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_INS, 202, signIn("claude", "STARTING", null, null, null));
        platform.answer("GET", SIGN_IN, signIn("claude", "AWAITING_CODE",
                "https://claude.com/cai/oauth/authorize?code=true&state=x", null, null));
        platform.answer("POST", SIGN_IN + "/code", 202, signIn("claude", "SUCCEEDED", null, null, null));
        time.afterSleep = () -> platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", null));

        Result signIn = run(stdin("the-code\n"), "targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("Open this URL in a browser and sign in:")
                .contains("https://claude.com/cai/oauth/authorize?code=true&state=x")
                .contains("Paste the code the browser shows:")
                .contains("is signed in.");
        JsonNode sent = JSON.readTree(platform.requests("POST", SIGN_IN + "/code").getFirst().body());
        assertThat(sent.path("code").asText()).isEqualTo("the-code");
        JsonNode started = JSON.readTree(platform.requests("POST", SIGN_INS).getFirst().body());
        assertThat(started.path("harness").asText()).isEqualTo("claude");
    }

    @Test
    void signInPostsTheCodeOnceWhileTheRunnerIsStillPassingItOn() throws Exception {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_INS, 202, signIn("claude", "AWAITING_CODE",
                "https://claude.com/cai/oauth/authorize?code=true&state=x", null, null));
        platform.answer("POST", SIGN_IN + "/code", 202, signIn("claude", "AWAITING_CODE",
                "https://claude.com/cai/oauth/authorize?code=true&state=x", null, null));
        time.afterSleep = () -> {
            platform.answer("GET", SIGN_IN, signIn("claude", "SUCCEEDED", null, null, null));
            platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", null));
        };

        Result signIn = run(stdin("first-code\nsecond-code\n"), "targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(platform.requests("POST", SIGN_IN + "/code")).hasSize(1);
        JsonNode sent = JSON.readTree(platform.requests("POST", SIGN_IN + "/code").getFirst().body());
        assertThat(sent.path("code").asText()).isEqualTo("first-code");
    }

    private static String targetKimi(String kimiLogin) {
        return target("ABSENT", null).replace("\"kimi\":\"ABSENT\"", "\"kimi\":\"" + kimiLogin + "\"");
    }

    @Test
    void signInKimiHappyPathPrintsTheUrlAndCodeAndNeverReadsStdin() {
        platform.answer("GET", "/system/api/targets/" + TARGET, targetKimi("ABSENT"));
        platform.answer("POST", SIGN_INS, 202, signIn("kimi", "AWAITING_BROWSER",
                "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234", "ABCD-1234", null));
        time.afterSleep = () -> {
            if (time.sleeps.size() == 1) {
                platform.answer("GET", SIGN_IN, signIn("kimi", "SUCCEEDED",
                        "https://www.kimi.com/code/authorize_device?user_code=ABCD-1234", "ABCD-1234", null));
            } else if (time.sleeps.size() == 2) {
                platform.answer("GET", "/system/api/targets/" + TARGET, targetKimi("PRESENT"));
            }
        };

        Result signIn = run(InputStream.nullInputStream(), "targets", "sign-in", "--target", TARGET, "--harness",
                "kimi");

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("Open this URL in a browser and sign in:")
                .contains("https://www.kimi.com/code/authorize_device?user_code=ABCD-1234")
                .contains("Enter the code ABCD-1234 there.")
                .contains("is signed in.")
                .doesNotContain("Paste the code the browser shows:");
    }

    @Test
    void signInFailedNamesTheStateAndDetail() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_INS, 202, signIn("claude", "FAILED", null, null,
                "Login failed: Request failed with status code 400"));

        Result signIn = run("targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).isEqualTo(1);
        assertThat(signIn.err()).contains("FAILED").contains("Login failed: Request failed with status code 400");
    }

    @Test
    void signInTimeoutDeletesTheSignInAndNamesTheLastState() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_INS, 202, signIn("claude", "STARTING", null, null, null));
        platform.answer("GET", SIGN_IN, signIn("claude", "STARTING", null, null, null));
        platform.answer("DELETE", SIGN_IN, 202, signIn("claude", "CANCELLED", null, null, null));

        Result signIn = run("targets", "sign-in", "--target", TARGET, "--timeout", "1s");

        assertThat(signIn.exit()).isEqualTo(1);
        assertThat(signIn.err()).contains("still STARTING after 1s");
        assertThat(platform.requests("DELETE", SIGN_IN)).isNotEmpty();
    }

    @Test
    void signInResumesWithSignInAndCodeSkippingThePost() throws Exception {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_IN + "/code", 202, signIn("claude", "SUCCEEDED", null, null, null));
        time.afterSleep = () -> platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", null));

        Result signIn = run("targets", "sign-in", "--target", TARGET, "--sign-in", SIGNIN, "--code", "654321");

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("is signed in.");
        assertThat(platform.requests("POST", SIGN_INS)).isEmpty();
        JsonNode sent = JSON.readTree(platform.requests("POST", SIGN_IN + "/code").getFirst().body());
        assertThat(sent.path("code").asText()).isEqualTo("654321");
    }

    @Test
    void signInResumesWithSignInAloneByPolling() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("GET", SIGN_IN, signIn("claude", "SUCCEEDED", null, null, null));
        time.afterSleep = () -> platform.answer("GET", "/system/api/targets/" + TARGET, target("PRESENT", null));

        Result signIn = run("targets", "sign-in", "--target", TARGET, "--sign-in", SIGNIN);

        assertThat(signIn.exit()).as(signIn.err()).isZero();
        assertThat(signIn.out()).contains("is signed in.");
        assertThat(platform.requests("POST", SIGN_INS)).isEmpty();
    }

    @Test
    void signInCodeWithoutSignInIsAUsageErrorBeforeAnyRequest() {
        Result signIn = run("targets", "sign-in", "--target", TARGET, "--code", "123456");

        assertThat(signIn.exit()).isEqualTo(2);
        assertThat(signIn.err()).contains("--code needs --sign-in");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void signInWithEmptyStdinAtAwaitingCodeNamesTheResumeCommandAndFails() {
        platform.answer("GET", "/system/api/targets/" + TARGET, target("ABSENT", null));
        platform.answer("POST", SIGN_INS, 202, signIn("claude", "AWAITING_CODE",
                "https://claude.com/cai/oauth/authorize?code=true", null, null));

        Result signIn = run(InputStream.nullInputStream(), "targets", "sign-in", "--target", TARGET);

        assertThat(signIn.exit()).isEqualTo(1);
        assertThat(signIn.err()).contains("No code given.")
                .contains("qits targets sign-in --target " + TARGET + " --sign-in " + SIGNIN + " --code <code>");
    }

    // --- create and rotate-token (qits-1574) ---

    private static String registration() {
        return "{\"target\":" + target("ABSENT", null).replace("\"registered\":true", "\"registered\":false")
                + ",\"registrationToken\":\"" + REG_TOKEN + "\",\"installLine\":\"" + INSTALL_LINE + "\"}";
    }

    @Test
    void createSendsTheNameAndOnlyTheGivenMinimumsAndPrintsTheInstallLineAlone() throws Exception {
        platform.answer("POST", "/system/api/targets", 201, registration());

        Result created = run("targets", "create", "--name", "localhost", "--description", "the build box",
                "--min-ci", "2", "--min-desk", "1");

        assertThat(created.exit()).as(created.err()).isZero();
        assertThat(created.out()).isEqualTo(INSTALL_LINE + System.lineSeparator());
        assertThat(created.err()).doesNotContain(REG_TOKEN).contains("localhost").contains(TARGET)
                .contains("shown only this once").contains("target's own machine");
        JsonNode sent = JSON.readTree(platform.requests("POST", "/system/api/targets").getFirst().body());
        assertThat(sent.path("name").asText()).isEqualTo("localhost");
        assertThat(sent.path("description").asText()).isEqualTo("the build box");
        assertThat(sent.path("minimums").path("ci").asInt()).isEqualTo(2);
        assertThat(sent.path("minimums").path("desk").asInt()).isEqualTo(1);
        assertThat(sent.path("minimums").has("workspace")).isFalse();
    }

    @Test
    void createWithSlotsSendsThem() throws Exception {
        platform.answer("POST", "/system/api/targets", 201, registration());

        Result created = run("targets", "create", "--name", "localhost", "--slots", "8");

        assertThat(created.exit()).as(created.err()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", "/system/api/targets").getFirst().body());
        assertThat(sent.path("slots").asInt()).isEqualTo(8);
    }

    @Test
    void createWithNoMinimumsSendsNoMinimumsAtAll() throws Exception {
        platform.answer("POST", "/system/api/targets", 201, registration());

        Result created = run("targets", "create", "--name", "localhost");

        assertThat(created.exit()).as(created.err()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", "/system/api/targets").getFirst().body());
        assertThat(sent.has("minimums")).isFalse();
        assertThat(sent.has("description")).isFalse();
        assertThat(sent.path("name").asText()).isEqualTo("localhost");
    }

    @Test
    void createJsonIsTheServicesAnswer() throws Exception {
        platform.answer("POST", "/system/api/targets", 201, registration());

        Result created = run("targets", "create", "--name", "localhost", "-o", "json");

        assertThat(created.exit()).as(created.err()).isZero();
        JsonNode printed = JSON.readTree(created.out());
        assertThat(printed).isEqualTo(JSON.readTree(registration()));
        assertThat(created.err()).doesNotContain(REG_TOKEN);
    }

    @Test
    void createRefusedForAnAgentSaysSoWithoutAToken() {
        platform.answer("POST", "/system/api/targets", 403,
                "{\"code\":\"FORBIDDEN\",\"message\":\"this credential is qits:agent\"}");

        Result refused = run("targets", "create", "--name", "localhost");

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("HTTP 403");
        assertThat(refused.out()).isEmpty();
    }

    @Test
    void createWithATakenNameIsRefused() {
        platform.answer("POST", "/system/api/targets", 409,
                "{\"code\":\"TARGET_NAME_TAKEN\",\"message\":\"a target named localhost exists\"}");

        Result refused = run("targets", "create", "--name", "localhost");

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("HTTP 409").contains("a target named localhost exists");
        assertThat(refused.out()).isEmpty();
    }

    @Test
    void anAnswerWithoutAnInstallLineFailsWithoutQuotingIt() {
        platform.answer("POST", "/system/api/targets", 201,
                "{\"target\":{\"id\":\"" + TARGET + "\",\"name\":\"localhost\"},\"registrationToken\":\""
                        + REG_TOKEN + "\"}");

        Result broken = run("targets", "create", "--name", "localhost");

        assertThat(broken.exit()).isEqualTo(1);
        assertThat(broken.err()).contains("carries no install line").doesNotContain(REG_TOKEN);
        assertThat(broken.out()).isEmpty();
    }

    @Test
    void rotateTokenResolvesTheNameAndPrintsTheInstallLineAlone() {
        platform.answer("GET", "/system/api/targets", "[" + target("ABSENT", null) + "]");
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", registration());

        Result rotated = run("targets", "rotate-token", "--target", "localhost");

        assertThat(rotated.exit()).as(rotated.err()).isZero();
        assertThat(rotated.out()).isEqualTo(INSTALL_LINE + System.lineSeparator());
        assertThat(rotated.err()).doesNotContain(REG_TOKEN).contains("localhost").contains(TARGET)
                .contains("shown only this once");
        assertThat(platform.requests("GET", "/system/api/targets")).hasSize(1);
        var posted = platform.requests("POST", "/system/api/targets/" + TARGET + "/registration-token");
        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst().body()).isNullOrEmpty();
    }

    @Test
    void rotateTokenJsonIsTheServicesAnswer() throws Exception {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", registration());

        Result rotated = run("targets", "rotate-token", "--target", TARGET, "-o", "json");

        assertThat(rotated.exit()).as(rotated.err()).isZero();
        assertThat(JSON.readTree(rotated.out())).isEqualTo(JSON.readTree(registration()));
        assertThat(platform.requests("GET", "/system/api/targets")).isEmpty();
    }

    @Test
    void rotateTokenReinstallsARegisteredTarget() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", registration());

        Result rotated = run("targets", "rotate-token", "--target", TARGET);

        assertThat(rotated.exit()).as(rotated.err()).isZero();
        assertThat(rotated.out()).isEqualTo(INSTALL_LINE + System.lineSeparator());
        var posted = platform.requests("POST", "/system/api/targets/" + TARGET + "/registration-token");
        assertThat(posted).hasSize(1);
        assertThat(posted.getFirst().body()).isNullOrEmpty();
    }

    @Test
    void rotateTokenWithSlotsSendsThemAsTheBody() throws Exception {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", registration());

        Result rotated = run("targets", "rotate-token", "--target", TARGET, "--slots", "8");

        assertThat(rotated.exit()).as(rotated.err()).isZero();
        var posted = platform.requests("POST", "/system/api/targets/" + TARGET + "/registration-token");
        assertThat(posted).hasSize(1);
        assertThat(JSON.readTree(posted.getFirst().body()).path("slots").asInt()).isEqualTo(8);
    }

    @Test
    void rotateTokenWithSlotsBelowTheMinimumsSumIsRefused() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", 400,
                "{\"code\":\"SLOTS_BELOW_MINIMUMS\",\"message\":\"slots must cover the minimums\"}");

        Result refused = run("targets", "rotate-token", "--target", TARGET, "--slots", "1");

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("HTTP 400").contains("slots must cover the minimums");
        assertThat(refused.out()).isEmpty();
    }

    @Test
    void rotateTokenRefusedForAnAgent() {
        platform.answer("POST", "/system/api/targets/" + TARGET + "/registration-token", 403,
                "{\"message\":\"this credential is qits:agent\"}");

        Result refused = run("targets", "rotate-token", "--target", TARGET);

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("HTTP 403");
        assertThat(refused.out()).isEmpty();
    }

    @Test
    void rotateTokenHelpNamesTheReinstallAndNoLongerALREADY_REGISTERED() {
        Result help = run("targets", "rotate-token", "--help");

        assertThat(help.exit()).as(help.err()).isZero();
        assertThat(help.out()).contains("--slots").contains("REGISTERED target").contains("reinstalls its runner")
                .contains("HTTP 400");
        assertThat(help.out()).doesNotContain("ALREADY_REGISTERED");
    }

    @Test
    void createHelpNamesSlots() {
        Result help = run("targets", "create", "--help");

        assertThat(help.exit()).as(help.err()).isZero();
        assertThat(help.out()).contains("--slots");
    }

    @Test
    void theCommandsThatPrintATokenAreLocal() {
        for (Class<?> command : new Class<?>[] {TargetsCommand.CreateCommand.class,
                TargetsCommand.RotateTokenCommand.class}) {
            TuiCommand marker = command.getAnnotation(TuiCommand.class);
            assertThat(marker).as(command.getSimpleName()).isNotNull();
            assertThat(marker.interaction()).as(command.getSimpleName()).isEqualTo(Interaction.LOCAL);
        }
    }

    // --- docker (qits-1646) ---

    private static final String CONTAINER = "qits-system-runner-work-ws-1-a1b2c3d4";
    private static final String DOCKER = "/system/api/targets/" + TARGET + "/docker";

    @Test
    void dockerPsIsATableAndJson() throws Exception {
        platform.answer("GET", DOCKER + "/containers", "[{\"Names\":\"" + CONTAINER
                + "\",\"Image\":\"qits/workspace:2026\",\"Status\":\"Up 2 hours\"}]");

        Result table = run("targets", "docker", "ps", "--target", TARGET);
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("NAME").contains(CONTAINER).contains("Up 2 hours");

        Result json = run("targets", "docker", "ps", "--target", TARGET, "-o", "json");
        assertThat(JSON.readTree(json.out()).get(0).path("Image").asText()).isEqualTo("qits/workspace:2026");
    }

    @Test
    void dockerPsWithNoContainersSaysSo() {
        platform.answer("GET", DOCKER + "/containers", "[]");
        Result table = run("targets", "docker", "ps", "--target", TARGET);
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("No containers.");
    }

    @Test
    void dockerInspectPrintsTheRedactedInspect() throws Exception {
        platform.answer("GET", DOCKER + "/containers/" + CONTAINER,
                "{\"Id\":\"abc\",\"Config\":{\"Env\":[\"TOKEN=***\"]}}");

        Result inspect = run("targets", "docker", "inspect", "--target", TARGET, CONTAINER);

        assertThat(inspect.exit()).as(inspect.err()).isZero();
        assertThat(JSON.readTree(inspect.out()).path("Id").asText()).isEqualTo("abc");
    }

    @Test
    void dockerLogsByTargetReadsTheTextAndNamesATruncation() {
        platform.answer("GET", DOCKER + "/containers/" + CONTAINER + "/logs", 200, "line one\nline two",
                "X-Qits-Truncated", "true");

        Result logs = run("targets", "docker", "logs", "--target", TARGET, CONTAINER, "--tail", "50",
                "--since", "2026-10-23T00:00:00Z");

        assertThat(logs.exit()).as(logs.err()).isZero();
        assertThat(logs.out()).isEqualTo("line one\nline two\n");
        assertThat(logs.err()).contains("cut off at 1 MiB");
        assertThat(platform.requests("GET", DOCKER + "/containers/" + CONTAINER + "/logs").getFirst().query())
                .isEqualTo("tail=50&since=2026-10-23T00%3A00%3A00Z");
    }

    @Test
    void dockerLogsByLeaseReadsTheLeasesOwnLog() {
        platform.answer("GET", "/system/api/leases/" + LEASE + "/logs", 200, "the lease's own log");

        Result logs = run("targets", "docker", "logs", "--lease", LEASE);

        assertThat(logs.exit()).as(logs.err()).isZero();
        assertThat(logs.out()).isEqualTo("the lease's own log\n");
        assertThat(platform.requests("GET", "/system/api/leases/" + LEASE + "/logs")).isNotEmpty();
    }

    @Test
    void dockerLogsNeedsExactlyOneOfTargetOrLease() {
        Result neither = run("targets", "docker", "logs", CONTAINER);
        assertThat(neither.exit()).isEqualTo(2);
        assertThat(neither.err()).contains("--target").contains("--lease");

        Result both = run("targets", "docker", "logs", "--target", TARGET, "--lease", LEASE, CONTAINER);
        assertThat(both.exit()).isEqualTo(2);

        Result leaseWithName = run("targets", "docker", "logs", "--lease", LEASE, CONTAINER);
        assertThat(leaseWithName.exit()).isEqualTo(2);
        assertThat(leaseWithName.err()).contains("takes no container name");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void dockerExecSendsArgvAndMapsTheExitCode() throws Exception {
        platform.answer("POST", DOCKER + "/containers/" + CONTAINER + "/exec",
                "{\"exitCode\":0,\"stdout\":\"hi\\n\",\"stderr\":\"\",\"truncated\":false}");

        Result exec = run("targets", "docker", "exec", "--target", TARGET, CONTAINER, "--", "echo", "hi");

        assertThat(exec.exit()).as(exec.err()).isZero();
        assertThat(exec.out()).isEqualTo("hi\n\n");
        JsonNode sent = JSON.readTree(platform.requests("POST", DOCKER + "/containers/" + CONTAINER + "/exec")
                .getFirst().body());
        assertThat(sent.path("argv")).isEqualTo(JSON.createArrayNode().add("echo").add("hi"));
    }

    @Test
    void dockerExecWithANonZeroExitCodeIsTheCommandsOwnExit() {
        platform.answer("POST", DOCKER + "/containers/" + CONTAINER + "/exec",
                "{\"exitCode\":3,\"stdout\":\"\",\"stderr\":\"boom\\n\",\"truncated\":false}");

        Result exec = run("targets", "docker", "exec", "--target", TARGET, CONTAINER, "--", "false");

        assertThat(exec.exit()).isEqualTo(3);
        assertThat(exec.err()).isEqualTo("boom\n\n");
    }

    @Test
    void dockerExecWithNoArgvIsRefusedBeforeAnyRequest() {
        Result exec = run("targets", "docker", "exec", "--target", TARGET, CONTAINER);
        assertThat(exec.exit()).isEqualTo(2);
        assertThat(exec.err()).contains("Name the command to run");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void dockerStopAndRmPrintADoneMessage() {
        platform.answer("POST", DOCKER + "/containers/" + CONTAINER + "/stop",
                "{\"name\":\"" + CONTAINER + "\",\"output\":\"" + CONTAINER + "\"}");
        Result stop = run("targets", "docker", "stop", "--target", TARGET, CONTAINER);
        assertThat(stop.exit()).as(stop.err()).isZero();
        assertThat(stop.out()).contains("Stopped " + CONTAINER);

        platform.answer("DELETE", DOCKER + "/containers/" + CONTAINER,
                "{\"name\":\"" + CONTAINER + "\",\"output\":\"" + CONTAINER + "\"}");
        Result rm = run("targets", "docker", "rm", "--target", TARGET, CONTAINER);
        assertThat(rm.exit()).as(rm.err()).isZero();
        assertThat(rm.out()).contains("Removed " + CONTAINER);
    }

    @Test
    void dockerVolumesAndVolume() throws Exception {
        platform.answer("GET", DOCKER + "/volumes",
                "[{\"Name\":\"" + CONTAINER + "\",\"Driver\":\"local\",\"Scope\":\"local\"}]");
        Result volumes = run("targets", "docker", "volumes", "--target", TARGET);
        assertThat(volumes.exit()).as(volumes.err()).isZero();
        assertThat(volumes.out()).contains("NAME").contains(CONTAINER).contains("local");

        platform.answer("GET", DOCKER + "/volumes/" + CONTAINER,
                "{\"Name\":\"" + CONTAINER + "\",\"Mountpoint\":\"/var/lib/docker/volumes/x\"}");
        Result volume = run("targets", "docker", "volume", "--target", TARGET, CONTAINER);
        assertThat(volume.exit()).as(volume.err()).isZero();
        assertThat(JSON.readTree(volume.out()).path("Name").asText()).isEqualTo(CONTAINER);
    }

    @Test
    void dockerAuditIsATableNewestFirstWithTheLimitAsQuery() {
        platform.answer("GET", DOCKER + "/audit", """
                [{"id":"00000000-0000-4000-8000-000000000060","at":"2026-10-23T09:00:00Z","actor":"agent",
                  "roles":"qits:admin","targetId":"%s","leaseId":null,"operation":"DOCKER_LOGS",
                  "resource":"%s","args":{"tail":50},"outcome":"OK","detail":null}]
                """.formatted(TARGET, CONTAINER));

        Result audit = run("targets", "docker", "audit", "--target", TARGET, "--limit", "20");

        assertThat(audit.exit()).as(audit.err()).isZero();
        assertThat(audit.out()).contains("OPERATION").contains("DOCKER_LOGS").contains(CONTAINER).contains("OK");
        assertThat(platform.requests("GET", DOCKER + "/audit").getFirst().query()).isEqualTo("limit=20");
    }

    @Test
    void dockerHelpNamesTheRolesAndTheAudit() {
        Result help = run("targets", "docker", "ps", "--help");

        assertThat(help.exit()).as(help.err()).isZero();
        String text = help.out().replaceAll("\\s+", " ");
        assertThat(text).contains("qits:desk-admin").contains("target_audit").contains("redacts");
    }
}
