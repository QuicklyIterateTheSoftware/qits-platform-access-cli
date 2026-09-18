package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.AgentCredential;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The other home of {@code qits git-credential}: a container, where there is no sign-in to read
 * and the commissioned credential answers.
 * <p>
 * The two things worth proving are that it answers at all — the defect was that it printed nothing
 * and the image had to carry a shell script — and that it answers <b>only</b> the injected host.
 * The image sets a global {@code credential.helper}, so Git runs this for every http remote a
 * checked-out repository names; handing the platform's bearer to an arbitrary host would be
 * exfiltration. So the host tests assert on the idp, not on stdout: the check must happen before
 * anything is minted.
 */
class GitCredentialInPlatformTest {

    private static final Instant T0 = Instant.parse("2026-09-18T10:00:00Z");
    private static final String HOST = "githost.dev.internal:8080";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private GitCredentialFile store;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new GitCredentialFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put(Mode.CLIENT_ID, idp.commissionedId);
        env.put(Mode.CLIENT_SECRET, idp.commissionedSecret);
        env.put("QITS_GIT_AUTH_TOKEN_URL", idp.url() + "/token");
        env.put("QITS_GIT_AUTH_HOST", HOST);
    }

    @AfterEach
    void stop() {
        idp.close();
        // stdout carries a token by design, for Git; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result credential(String action, String stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        assertThat(context.mode()).isEqualTo(Mode.IN_PLATFORM);
        int exit = TestCli.execute(context, "git-credential", action);
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String request(String host) {
        return "protocol=https\nhost=" + host + "\npath=git/qits/qits-ci.git\n\n";
    }

    /** What the idp would hand this container, minted apart from the command under test. */
    private String theContainersToken() throws Exception {
        String base = idp.url().substring(0, idp.url().length() - "/idp".length());
        return new AgentCredential(base, idp.commissionedId, idp.commissionedSecret, "qits-platform", time).bearer();
    }

    @Test
    void getAnswersTheInjectedHostFromTheContainersOwnCredential() throws Exception {
        Result r = credential("get", request(HOST));

        assertThat(r.exit()).isZero();
        assertThat(idp.grants("client_credentials")).isEqualTo(1);
        assertThat(r.err()).isEmpty();
        assertThat(r.out()).isEqualTo("username=oauth2\npassword=" + theContainersToken() + "\n\n");
    }

    /**
     * A default port written out on one side and left off on the other is the same host. The shell
     * script this replaces compared the raw lines and went quiet here.
     */
    @Test
    void anExplicitDefaultPortIsTheSameHost() throws Exception {
        env.put("QITS_GIT_AUTH_HOST", "https://githost.dev.internal:443/git");

        Result r = credential("get", request("githost.dev.internal"));

        assertThat(idp.grants("client_credentials")).isEqualTo(1);
        assertThat(r.out()).isEqualTo("username=oauth2\npassword=" + theContainersToken() + "\n\n");
    }

    @Test
    void noOtherHostIsAnswered() {
        assertThat(credential("get", request("github.com"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", request("githost.dev.internal:9090"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", request("githost.dev.internal"))).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", "protocol=ssh\nhost=" + HOST + "\n\n")).isEqualTo(new Result(0, "", ""));

        assertThat(idp.requests).as("the host is checked before anything is minted").isEmpty();
    }

    @Test
    void withoutTheInjectedHostNothingIsAnswered() {
        env.remove("QITS_GIT_AUTH_HOST");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        env.put("QITS_GIT_AUTH_HOST", "   ");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        env.put("QITS_GIT_AUTH_HOST", "not a host at all");
        assertThat(credential("get", request(HOST))).isEqualTo(new Result(0, "", ""));

        assertThat(idp.requests).as("no variable is never 'any host'").isEmpty();
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

    @Test
    void anIdpThatRefusesTheClientSaysSoOnStderrAndLetsGitCarryOn() {
        idp.commissionedSecret = "another secret";

        Result r = credential("get", request(HOST));

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("The idp refused the workspace credential (HTTP 401", "invalid_client");
        assertThat(r.err()).doesNotContain(env.get(Mode.CLIENT_SECRET)).doesNotContain(FakeIdp.SECRET);
    }
}
