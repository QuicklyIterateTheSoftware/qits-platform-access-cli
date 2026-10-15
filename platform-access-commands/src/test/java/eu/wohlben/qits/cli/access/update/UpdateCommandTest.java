package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits update} end to end against {@link FakePlatform} as qits-artifacts: the daemon list,
 * the HEAD and the download, with the session's bearer on each.
 */
class UpdateCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-15T10:00:00Z");
    private static final String RUNNING = "2026.919.40352";
    private static final String LATEST = "2026.1015.175513";
    private static final String TOKEN = "SECRET-access-update";
    private static final String FILE = "/artifacts/daemons/qits-platform-access-cli/" + LATEST;

    @TempDir
    Path home;

    private FakePlatform store;
    private FakeTime time;
    private Path self;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        store = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.resolve("config").toString());
        env.put("XDG_CACHE_HOME", home.resolve("cache").toString());
        env.put("QITS_ARTIFACTS_URL", store.url());
        new SessionFile(home.resolve("config").resolve("qits")).write(new Session("https://idp.qits.example.eu/idp",
                "qits-cli", TOKEN, T0.plus(Duration.ofMinutes(10)), "SECRET-refresh", T0.plus(Duration.ofDays(30))));

        Path bin = Files.createDirectories(home.resolve("bin"));
        self = bin.resolve("qits");
        Files.writeString(self, script(RUNNING));
        Files.setPosixFilePermissions(self, PosixFilePermissions.fromString("rwxr-xr-x"));
        store.answer("GET", "/artifacts/api/repositories/daemons/daemons",
                "{\"daemons\":[{\"name\":\"qits-ci-daemon\",\"latestVersion\":\"2026.1.1\"},"
                        + "{\"name\":\"qits-platform-access-cli\",\"latestVersion\":\"" + LATEST + "\",\"versions\":[]}]}");
        serve(script(LATEST));
    }

    @AfterEach
    void stop() {
        store.close();
    }

    private static String script(String version) {
        return "#!/bin/sh\necho " + version + "\n";
    }

    private void serve(String file) throws IOException {
        byte[] bytes = file.getBytes(StandardCharsets.UTF_8);
        Path scratch = Files.createTempFile(home, "served", ".bin");
        Files.write(scratch, bytes);
        String digest = "sha256:" + SelfUpdate.sha256(scratch);
        store.answer("HEAD", FILE, 200, "", "Content-Length", String.valueOf(bytes.length),
                "Docker-Content-Digest", digest);
        store.answer("GET", FILE, 200, file, "Docker-Content-Digest", digest);
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof UpdateCommand command) {
                    command.useContext(context);
                    command.binary = () -> Optional.of(self);
                    command.running = () -> Optional.of(RUNNING);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(context.out(), true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(context.err(), true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).as("no token is printed").doesNotContain("SECRET");
        return result;
    }

    @Test
    void itReplacesTheBinaryAndSaysFromWhatToWhat() throws IOException {
        Result r = run("update");

        assertThat(r).isEqualTo(new Result(0, "Updated qits from " + RUNNING + " to " + LATEST + "."
                + System.lineSeparator(), ""));
        assertThat(Files.readString(self)).isEqualTo(script(LATEST));
        assertThat(store.requests).extracting(FakePlatform.Request::authorization).containsOnly("Bearer " + TOKEN);
        assertThat(store.requests).extracting(FakePlatform.Request::method).containsExactly("GET", "HEAD", "GET");
    }

    @Test
    void upToDateSaysSo() {
        env.put("QITS_ARTIFACTS_URL", store.url() + "/");
        store.answer("GET", "/artifacts/api/repositories/daemons/daemons",
                "{\"daemons\":[{\"name\":\"qits-platform-access-cli\",\"latestVersion\":\"" + RUNNING + "\"}]}");

        assertThat(run("update")).isEqualTo(new Result(0, "qits is up to date (" + RUNNING + ")."
                + System.lineSeparator(), ""));
    }

    @Test
    void checkReportsAndDownloadsNothing() throws IOException {
        Result r = run("update", "--check");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains(LATEST + " is released");
        assertThat(store.requests("GET", FILE)).isEmpty();
        assertThat(Files.readString(self)).isEqualTo(script(RUNNING));
    }

    @Test
    void aDigestTheDownloadDoesNotHaveKeepsTheOldBinary() throws IOException {
        store.answer("HEAD", FILE, 200, "", "Content-Length", String.valueOf(script(LATEST).length()),
                "Docker-Content-Digest", "sha256:" + "a".repeat(64));

        Result r = run("update");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("does not have the sha256 digest").contains("Nothing was replaced");
        assertThat(Files.readString(self)).isEqualTo(script(RUNNING));
        try (var files = Files.list(self.getParent())) {
            assertThat(files).containsExactly(self);
        }
    }

    @Test
    void aStoreThatRefusesTheDownloadSaysSo() throws IOException {
        store.answer("GET", FILE, 403, "{\"message\":\"no\"}");

        Result r = run("update");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403").contains(FILE);
        assertThat(Files.readString(self)).isEqualTo(script(RUNNING));
    }

    @Test
    void insideThePlatformItRefuses() {
        env.put(Mode.CLIENT_ID, "id");
        env.put(Mode.CLIENT_SECRET, "secret");

        Result r = run("update");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).startsWith("qits does not update itself inside the platform");
        assertThat(store.requests).isEmpty();
    }

    @Test
    void withAWorkspaceTokenOrInTheMcpServiceItRefuses() {
        env.put(Mode.TOKEN, "a workspace token");
        assertThat(run("update").exit()).isEqualTo(2);
        env.remove(Mode.TOKEN);
        env.put(CliContext.MCP_SERVICE, "true");
        Result r = run("update", "--check");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).startsWith("qits does not update itself in the MCP service.");
        assertThat(store.requests).isEmpty();
    }

    @Test
    void notSignedInIsAUsageError() throws IOException {
        Files.delete(home.resolve("config/qits/t.json"));

        Result r = run("update");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("qits login");
    }

    @Test
    void theBackgroundRunPrintsNothingAndRecordsAFailureForTheNextCommand() throws IOException {
        store.answer("GET", "/artifacts/api/repositories/daemons/daemons", 503, "{\"message\":\"down\"}");

        Result r = run("update", "--background");

        assertThat(r).isEqualTo(new Result(0, "", ""));
        UpdateState state = UpdateState.read(home.resolve("config/qits"));
        assertThat(state.checkedAt()).isEqualTo(T0);
        assertThat(state.failure()).startsWith("qits could not update itself: GET ").contains("HTTP 503")
                .endsWith("Run `qits update` to try again.").doesNotContain("\n");
    }

    @Test
    void theBackgroundRunReplacesTheBinaryAndClearsAnEarlierFailure() throws IOException {
        new UpdateState(T0.minus(Duration.ofHours(2)), "an earlier failure").write(home.resolve("config/qits"));

        assertThat(run("update", "--background")).isEqualTo(new Result(0, "", ""));

        assertThat(Files.readString(self)).isEqualTo(script(LATEST));
        assertThat(UpdateState.read(home.resolve("config/qits"))).isEqualTo(new UpdateState(T0, null));
    }

    @Test
    void theBackgroundRunInsideThePlatformDoesNothing() {
        env.put(Mode.CLIENT_ID, "id");
        env.put(Mode.CLIENT_SECRET, "secret");

        assertThat(run("update", "--background")).isEqualTo(new Result(0, "", ""));
        assertThat(store.requests).isEmpty();
    }

    @Test
    void theHelpNamesTheVariableThatTurnsItOff() {
        Result r = run("update", "--help");

        assertThat(r.out()).contains("QITS_AUTO_UPDATE=false").contains("--check").doesNotContain("--background");
    }
}
