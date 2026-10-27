package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-platform home of {@code qits git-credential} ({@link Mode#OVERRIDE}): there is no sign-in
 * to read, and the credential the context holds answers.
 * <p>
 * The two things worth proving are that it answers at all, and that it answers <b>only</b> the
 * injected host. Git may run this for every http remote a checked-out repository names; handing the
 * platform's bearer to an arbitrary host would be exfiltration. So the host tests assert on the
 * credential, not on stdout: the check must happen before the bearer is asked for.
 */
class GitCredentialInPlatformTest {

    private static final Instant T0 = Instant.parse("2026-09-18T10:00:00Z");
    private static final String HOST = "githost.dev.internal:8080";
    private static final String BEARER = "the bearer this context was handed";

    @TempDir
    Path home;

    private FakeTime time;
    private GitCredentialFile store;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();
    private final AtomicInteger asked = new AtomicInteger();
    private final Credential handed = () -> {
        asked.incrementAndGet();
        return BEARER;
    };

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() {
        time = new FakeTime(T0);
        store = new GitCredentialFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put(Mode.OVERRIDE, "true");
        env.put("QITS_GIT_AUTH_HOST", HOST);
    }

    @AfterEach
    void stop() {
        // stdout carries a token by design, for Git; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(BEARER);
    }

    private Result credential(String action, String stdin) {
        return credential(action, stdin, true);
    }

    private Result credential(String action, String stdin, boolean withCredential) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        assertThat(context.mode()).isEqualTo(Mode.IN_PLATFORM);
        int exit = TestCli.execute(withCredential ? context.withCredential(handed) : context, "git-credential", action);
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String request(String host) {
        return "protocol=https\nhost=" + host + "\npath=git/qits/qits-ci.git\n\n";
    }

    @Test
    void getAnswersTheInjectedHostWithTheCredentialTheContextHolds() {
        Result r = credential("get", request(HOST));

        assertThat(r.exit()).isZero();
        assertThat(asked).hasValue(1);
        assertThat(r.err()).isEmpty();
        assertThat(r.out()).isEqualTo("username=oauth2\npassword=" + BEARER + "\n\n");
    }

    /** A default port written out on one side and left off on the other is the same host. */
    @Test
    void anExplicitDefaultPortIsTheSameHost() {
        env.put("QITS_GIT_AUTH_HOST", "https://githost.dev.internal:443/git");

        Result r = credential("get", request("githost.dev.internal"));

        assertThat(asked).hasValue(1);
        assertThat(r.out()).isEqualTo("username=oauth2\npassword=" + BEARER + "\n\n");
    }

    @Test
    void noOtherHostIsAnswered() {
        assertThat(credential("get", request("github.com"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", request("githost.dev.internal:9090"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", request("githost.dev.internal"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", "protocol=ssh\nhost=" + HOST + "\n\n")).isEqualTo(new Result(0, "", ""));

        assertThat(asked).as("the host is checked before the bearer is asked for").hasValue(0);
    }

    @Test
    void withoutTheInjectedHostNothingIsAnswered() {
        env.remove("QITS_GIT_AUTH_HOST");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        env.put("QITS_GIT_AUTH_HOST", "   ");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        env.put("QITS_GIT_AUTH_HOST", "not a host at all");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        assertThat(asked).as("no variable is never 'any host'").hasValue(0);
    }

    /** The agent's config folder is becoming a git repository: nothing of this may be committable. */
    @Test
    void nothingIsWrittenToDiskByAnyAction() {
        credential("get", request(HOST));
        credential("store", request(HOST).replace("\n\n", "\nusername=oauth2\npassword=something\n\n"));
        credential("erase", request(HOST).replace("\n\n", "\nusername=oauth2\npassword=something\n\n"));

        assertThat(store.path()).doesNotExist();
        assertThat(store.lockPath()).doesNotExist();
    }

    /** In-platform has no credential of its own: with none handed it says so and lets Git carry on. */
    @Test
    void withNoCredentialHandedItSaysSoOnStderrAndLetsGitCarryOn() {
        Result r = credential("get", request(HOST), false);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("There is no credential in this environment", Mode.OVERRIDE, Mode.TOKEN);
    }
}
