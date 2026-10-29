package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeSocketServer;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.access.terminal.FakeTerminal;
import eu.wohlben.qits.cli.access.terminal.LocalTerminal;
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
 * `qits workspaces attach` and the two commands that attach at once (`agents sign-in open --attach`,
 * `agents harness --attach`), as a person types them, against one fake that serves both the doors and
 * the terminal socket on the same address, as qits-workspaces does. The attachment's own behaviour is
 * {@link eu.wohlben.qits.cli.access.terminal.TerminalAttachTest}'s; this is which command it attaches to, and what is refused first.
 */
class AttachCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AGENT = "00000000-0000-4000-8000-0000000000a1";
    private static final String COMMAND = "00000000-0000-4000-8000-000000000100";
    private static final String SOCKET = "/workspaces/container/1/terminal/commands/" + COMMAND;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeSocketServer server;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    /** What the attaching commands get as the caller's terminal; null keeps the real decision. */
    private LocalTerminal terminal;

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        server = new FakeSocketServer();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_WORKSPACES_URL", server.url());
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        server.close();
        idp.close();
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
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                if (terminal != null) {
                    LocalTerminal.Factory fake = given -> terminal;
                    switch (made) {
                        case WorkspacesCommand.AttachCommand attach -> attach.terminals = fake;
                        case AgentCommands.AttachCommand attach -> attach.terminals = fake;
                        case WorkspaceAgentsCommand.SignInOpenCommand open -> open.terminals = fake;
                        case WorkspaceAgentsCommand.HarnessCommand harness -> harness.terminals = fake;
                        default -> {
                        }
                    }
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(context.out(), true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(context.err(), true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    /** The socket: the daemon says something and the command ends. */
    private void aTerminalThatSaysAndEnds(String said) {
        server.scripts.add(c -> {
            c.accept();
            c.send(said);
            c.close(1000, "");
        });
    }

    @Test
    void attachWithoutATerminalStreamsReadOnlyAndEndsWithTheCommand() {
        aTerminalThatSaysAndEnds("building...\r\ndone\r\n");

        Result attached = run("workspaces", "attach", COMMAND, "--workspace", "1");

        assertThat(attached.exit()).as(attached.err()).isZero();
        assertThat(attached.out()).isEqualTo("building...\r\ndone\r\n");
        assertThat(server.upgrades).singleElement().satisfies(upgrade -> {
            assertThat(upgrade.path()).isEqualTo(SOCKET);
            assertThat(upgrade.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        });
        assertThat(server.connections.getFirst().texts).as("stdin is no terminal: nothing is sent").isEmpty();
    }

    @Test
    void agentsAttachOpensTheAgentsTerminalThroughTheAgentProxy() throws Exception {
        terminal = new FakeTerminal();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("> ");
            c.close(1000, "");
        });

        Result attached = run("agents", "attach", AGENT);

        assertThat(attached.exit()).as(attached.err()).isZero();
        assertThat(server.upgrades).singleElement().satisfies(upgrade -> {
            assertThat(upgrade.path()).isEqualTo("/workspaces/agent/" + AGENT + "/terminal");
            assertThat(upgrade.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        });
        assertThat(server.connections.getFirst().texts.getFirst()).contains("\"type\":\"resize\"");
        assertThat(attached.out()).isEqualTo("> ");
        assertThat(attached.err()).contains("attached to agent " + AGENT).contains("the harness");
        assertThat(((FakeTerminal) terminal).restored()).isTrue();
    }

    @Test
    void agentsAttachNamesTheAgent() {
        Result none = run("agents", "attach");

        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Name the agent");
        assertThat(server.upgrades).isEmpty();
    }

    @Test
    void stdinThatIsNoTerminalIsReadOnly() {
        CliContext piped = new CliContext(Map.of(), InputStream.nullInputStream(), System.out, System.err, time, time,
                TokenClient::new, stop -> { });

        assertThat(LocalTerminal.of(piped)).isSameAs(LocalTerminal.READ_ONLY);
        assertThat(LocalTerminal.of(piped).interactive()).isFalse();
    }

    @Test
    void attachNeedsTheSlotAndTheCommand() {
        Result noSlot = run("workspaces", "attach", COMMAND);
        Result blank = run("workspaces", "attach", " ", "--workspace", "1");

        assertThat(noSlot.exit()).isEqualTo(2);
        assertThat(noSlot.err()).contains("--workspace");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("Name the command");
        assertThat(server.upgrades).isEmpty();
    }

    @Test
    void signInOpenAttachOpensTheSignInAndAttachesToTheCommandItStarted() throws Exception {
        terminal = new FakeTerminal();
        server.scripts.add(c -> c.answer("""
                {"command":{"id":"%s","kind":"TERMINAL","status":"RUNNING"}}""".formatted(COMMAND)));
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("Paste code here if prompted > ");
            c.close(1000, "");
        });

        Result opened = run("workspaces", "agents", "sign-in", "open", "--workspace", "1", "--harness", "claude",
                "--attach");

        assertThat(opened.exit()).as(opened.err()).isZero();
        assertThat(server.upgrades).extracting(FakeSocketServer.Upgrade::path)
                .containsExactly("/workspaces/container/1/agents/sign-in", SOCKET);
        assertThat(JSON.readTree(server.connections.getFirst().texts.getFirst()))
                .isEqualTo(JSON.readTree("{\"agentType\":\"CLAUDE\"}"));
        assertThat(server.connections.get(1).texts.getFirst()).contains("\"type\":\"resize\"");
        assertThat(opened.out()).isEqualTo("Paste code here if prompted > ");
        assertThat(opened.err()).contains("Opened the sign-in terminal on slot 1: command " + COMMAND + ". Attaching.")
                .contains("the command ended");
        assertThat(((FakeTerminal) terminal).restored()).isTrue();
    }

    @Test
    void signInOpenWithoutAttachSaysHowToAttach() {
        server.scripts.add(c -> c.answer("""
                {"command":{"id":"%s","kind":"TERMINAL","status":"RUNNING"}}""".formatted(COMMAND)));

        Result opened = run("workspaces", "agents", "sign-in", "open", "--workspace", "1");

        assertThat(opened.exit()).as(opened.err()).isZero();
        assertThat(opened.out()).contains("command " + COMMAND)
                .contains("qits workspaces attach " + COMMAND + " --workspace 1")
                .doesNotContain("landing app");
        assertThat(server.upgrades).hasSize(1);
    }

    @Test
    void harnessAttachFindsTheAgentsTerminalAndAttaches() {
        server.scripts.add(c -> c.answer("{\"commandId\":\"%s\"}".formatted(COMMAND)));
        server.scripts.add(c -> c.answer("""
                {"entries":[{"command":{"id":"%s","kind":"TERMINAL","status":"RUNNING"}}]}""".formatted(COMMAND)));
        aTerminalThatSaysAndEnds("> ");

        Result attached = run("workspaces", "agents", "harness", AGENT, "--workspace", "1", "--attach");

        assertThat(attached.exit()).as(attached.err()).isZero();
        assertThat(server.upgrades).extracting(FakeSocketServer.Upgrade::path).containsExactly(
                "/workspaces/container/1/agent-worktrees/" + AGENT, "/workspaces/container/1/commands", SOCKET);
        assertThat(attached.out()).isEqualTo("> ");
        assertThat(attached.err()).contains("attaching to command " + COMMAND);
    }

    @Test
    void harnessAttachRefusesAChat() {
        server.scripts.add(c -> c.answer("{\"commandId\":\"%s\"}".formatted(COMMAND)));
        server.scripts.add(c -> c.answer("""
                {"entries":[{"command":{"id":"%s","kind":"CHAT","status":"RUNNING"}}]}""".formatted(COMMAND)));

        Result attached = run("workspaces", "agents", "harness", AGENT, "--workspace", "1", "--attach");

        assertThat(attached.exit()).isEqualTo(1);
        assertThat(attached.err()).contains("runs as a chat").contains("qits workspaces agents chat");
        assertThat(server.upgrades).hasSize(2);
    }

    @Test
    void theMcpServiceAttachesToNothingAndOpensNothing() {
        String previous = System.getProperty(CliContext.MCP_SERVICE);
        System.setProperty(CliContext.MCP_SERVICE, "true");
        try {
            Result open = run("workspaces", "agents", "sign-in", "open", "--workspace", "1", "--attach");
            Result harness = run("workspaces", "agents", "harness", AGENT, "--workspace", "1", "--attach");
            Result attach = run("workspaces", "attach", COMMAND, "--workspace", "1");
            Result agent = run("agents", "attach", AGENT);

            for (Result refused : new Result[] {open, harness, attach, agent}) {
                assertThat(refused.exit()).isEqualTo(2);
                assertThat(refused.err()).contains("needs a terminal");
            }
            assertThat(server.upgrades).as("nothing was opened").isEmpty();
        } finally {
            if (previous == null) {
                System.clearProperty(CliContext.MCP_SERVICE);
            } else {
                System.setProperty(CliContext.MCP_SERVICE, previous);
            }
        }
    }
}
