package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The home that already worked, proven to still work.
 * <p>
 * The risk of in-platform mode is not that it fails; it is that it quietly takes the workstation
 * with it. So this runs the ordinary commands with the commissioned variables <b>absent</b> and
 * asserts what must not have happened: no {@code client_credentials} grant, no client secret in any
 * form the idp saw, and no reading of anything but the session file.
 */
class BothHomesTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), TokenClient.CLIENT_ID,
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":null,"dns":null}}]}
                """.formatted(QITS));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private int run(ByteArrayOutputStream out, String... args) {
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(out, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> {
        });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setExecutionStrategy(new BrowserGuard(cli.getExecutionStrategy(), Mode.of(env)));
        return cli.execute(args);
    }

    @Test
    void withoutTheCommissionedPairThisIsAWorkstationAndTheSessionFileIsWhatItUses() {
        assertThat(Mode.of(env)).isEqualTo(Mode.WORKSTATION);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThat(run(out, "projects", "list")).isZero();

        assertThat(out.toString(StandardCharsets.UTF_8)).contains("qits platform");
        assertThat(platform.requests).isNotEmpty();
        assertThat(platform.requests.getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void nothingEverMintsWithAClientSecret() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(out, "projects", "list");
        // Force the one thing that does go to the idp on a workstation: a refresh.
        time.jump(Duration.ofMinutes(10));
        run(out, "projects", "list");

        assertThat(idp.grants("client_credentials")).isZero();
        assertThat(idp.requests).isNotEmpty();
        assertThat(idp.requests).allSatisfy(request -> {
            assertThat(request).doesNotContainKey("client_secret");
            assertThat(request.get("authorization-header")).isEqualTo("null");
        });
    }

    @Test
    void theBrowserCommandsAreUntouchedOnAWorkstation() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        run(out, "login", "--help");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("qits login")
                .doesNotContain(BrowserGuard.NO_BROWSER);
    }
}
