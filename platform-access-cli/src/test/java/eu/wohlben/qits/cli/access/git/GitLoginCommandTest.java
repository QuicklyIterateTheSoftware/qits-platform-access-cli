package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The command as a person runs it; the browser is the test, reading the printed address. */
class GitLoginCommandTest {

    private static final String ORIGIN = GitLoginFlowTest.ORIGIN;

    @TempDir
    Path home;

    private FakeIdp idp;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        env.put("XDG_CONFIG_HOME", home.toString());
        // `git config --global` writes here, never to the person's ~/.gitconfig.
        env.put("GIT_CONFIG_GLOBAL", home.resolve("gitconfig").toString());
    }

    @AfterEach
    void stop() {
        idp.close();
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Runs the command, and plays the browser once the address is on stdout. */
    private int gitLogin(String... extra) throws Exception {
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                new FakeTime(Instant.parse("2026-09-12T10:00:00Z")), new FakeTime(Instant.EPOCH), TokenClient::new, stop -> { });
        List<String> args = new java.util.ArrayList<>(List.of("git-login", "--idp-url", idp.url(), "--git-host",
                ORIGIN + "/git/qits", "--audience", "dev-qits-githost", "--no-browser", "--timeout", "20"));
        args.addAll(List.of(extra));
        CompletableFuture<Integer> run = CompletableFuture.supplyAsync(() -> TestCli.execute(context, args.toArray(String[]::new)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String address = null;
        while (address == null && System.nanoTime() < deadline && !run.isDone()) {
            address = out().lines().map(String::strip).filter(l -> l.contains("/authorize?")).findFirst().orElse(null);
            Thread.sleep(20);
        }
        assertThat(address).as("the printed sign-in address").isNotNull();
        GitLoginFlowTest.signIn(idp, "code-1", null).accept(address);
        return run.get(20, TimeUnit.SECONDS);
    }

    private List<String> helpers() throws Exception {
        Process git = new ProcessBuilder("git", "config", "--file", home.resolve("gitconfig").toString(), "--get-all",
                "credential." + ORIGIN + ".helper").start();
        List<String> values = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        git.waitFor();
        return values;
    }

    @Test
    void itPrintsTheGitSetupForThisHostOnly() throws Exception {
        assertThat(gitLogin()).isZero();

        assertThat(out()).contains("Signed in for Git pushes to " + ORIGIN)
                .contains("  git config --global --replace-all credential." + ORIGIN + ".helper ''")
                .contains("  git config --global --add credential." + ORIGIN + ".helper '!qits git-credential'");
        assertThat(new GitCredentialFile(home.resolve("qits")).find(ORIGIN)).isPresent();
        assertThat(home.resolve("gitconfig")).doesNotExist();
    }

    @Test
    void configureRunsItAndRunningItAgainChangesNothing() throws Exception {
        assertThat(gitLogin("--configure")).isZero();

        assertThat(out()).contains("Git is set up");
        assertThat(helpers()).containsExactly("", "!qits git-credential");

        GitSetup.configure(GitSetup.commands(ORIGIN, "qits"), env, new PrintStream(err, true, StandardCharsets.UTF_8));
        assertThat(helpers()).containsExactly("", "!qits git-credential");
    }

    @Test
    void aPathWithASpaceIsQuotedForGitAndTheShell() {
        List<List<String>> commands = GitSetup.commands(ORIGIN, "/home/a b/qits");
        assertThat(commands.get(1).getLast()).isEqualTo("!\"/home/a b/qits\" git-credential");
        assertThat(GitSetup.shellLine(commands.get(1))).endsWith("'!\"/home/a b/qits\" git-credential'");
    }
}
