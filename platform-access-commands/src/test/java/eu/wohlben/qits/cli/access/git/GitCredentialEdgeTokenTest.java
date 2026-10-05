package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.Mode;
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
 * The token home of {@code qits git-credential}: a workspace on a runner node, where the workspace
 * token answers the public git host of {@code QITS_DOMAIN} and no other. The helper is global, so
 * Git runs it for every remote — a token handed to any other host would be exfiltration.
 */
class GitCredentialEdgeTokenTest {

    private static final String TOKEN = "the workspace token";

    @TempDir
    Path home;

    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() {
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put(Mode.TOKEN, TOKEN);
        env.put("QITS_DOMAIN", "wohlben.eu");
    }

    private Result credential(String action, String stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        FakeTime time = new FakeTime(Instant.parse("2026-10-05T10:00:00Z"));
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        assertThat(context.mode()).isEqualTo(Mode.EDGE_TOKEN);
        int exit = TestCli.execute(context, "git-credential", action);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String request(String protocol, String host) {
        return "protocol=" + protocol + "\nhost=" + host + "\npath=git/qits/qits-ci.git\n\n";
    }

    @Test
    void getAnswersThePublicGitHostWithTheToken() {
        assertThat(credential("get", request("https", "githost.qits.wohlben.eu")))
                .isEqualTo(new Result(0, "username=oauth2\npassword=" + TOKEN + "\n\n", ""));
        assertThat(credential("get", request("https", "githost.qits.wohlben.eu:443")))
                .as("the default port written out is the same host")
                .isEqualTo(new Result(0, "username=oauth2\npassword=" + TOKEN + "\n\n", ""));
    }

    @Test
    void noOtherHostIsAnswered() {
        Result nothing = new Result(0, "", "");
        assertThat(credential("get", request("https", "github.com"))).isEqualTo(nothing);
        assertThat(credential("get", request("https", "githost.qits.example.org"))).isEqualTo(nothing);
        assertThat(credential("get", request("https", "githost.qits.wohlben.eu:8443"))).isEqualTo(nothing);
        assertThat(credential("get", request("http", "githost.qits.wohlben.eu"))).isEqualTo(nothing);
        // The injected host of the other home means nothing here.
        env.put("QITS_GIT_AUTH_HOST", "githost.dev.internal:8080");
        assertThat(credential("get", request("http", "githost.dev.internal:8080"))).isEqualTo(nothing);
    }

    @Test
    void aDomainThatCannotBeReadAnswersNothing() {
        env.put("QITS_DOMAIN", "dev.localhost");
        assertThat(credential("get", request("https", "githost.qits.dev.localhost"))).isEqualTo(new Result(0, "", ""));
        env.remove("QITS_DOMAIN");
        assertThat(credential("get", request("https", "githost.qits.wohlben.eu"))).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void storeAndEraseWriteNothing() {
        GitCredentialFile store = new GitCredentialFile(home.resolve("qits"));
        String withPassword = request("https", "githost.qits.wohlben.eu")
                .replace("\n\n", "\nusername=oauth2\npassword=" + TOKEN + "\n\n");
        assertThat(credential("store", withPassword)).isEqualTo(new Result(0, "", ""));
        assertThat(credential("erase", withPassword)).isEqualTo(new Result(0, "", ""));
        assertThat(store.path()).doesNotExist();
        assertThat(store.lockPath()).doesNotExist();
    }
}
