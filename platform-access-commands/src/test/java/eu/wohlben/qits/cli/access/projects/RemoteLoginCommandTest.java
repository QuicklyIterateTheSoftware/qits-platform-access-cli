package eu.wohlben.qits.cli.access.projects;

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
 * {@code qits repositories remote-login} as a person types it, against one fake that serves both the
 * projects doors and the sign-in socket on the same address, as qits-projects does. The attachment's
 * own behaviour is {@link eu.wohlben.qits.cli.access.terminal.TerminalAttachTest}'s; this is which
 * repository it signs in through, what goes over the socket, and what follows a clean close.
 */
class RemoteLoginCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROJECT_ID = "00000000-0000-4000-8000-0000000000f1";
    private static final String WRAPPER_ID = "00000000-0000-4000-8000-000000000001";
    private static final String SERVICE_ID = "00000000-0000-4000-8000-000000000002";
    private static final String PROJECTS = """
            {"entries":[{"project":{"id":"%s","name":"Qits","slug":"qits"}}]}""".formatted(PROJECT_ID);

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeSocketServer server;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    /** What the command gets as the caller's terminal; null keeps the real decision. */
    private LocalTerminal terminal;

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        server = new FakeSocketServer();
        time = new FakeTime(T0);
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", server.url());
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
                if (terminal != null && made instanceof RemoteLoginCommand login) {
                    login.terminals = given -> terminal;
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

    /** The project's repositories: the wrapper and a service, both with a backup remote. */
    private static String repositories(String serviceBackup) {
        return """
                {"entries":[
                  {"repository":{"id":"%s","name":"qits-qits","backupUrl":"https://forge.example/qits/qits-qits.git",
                    "lastBackup":{"outcome":"AUTH_REQUIRED","at":"2026-10-16T09:00:00Z","detail":"no credentials"}},
                   "declared":true},
                  {"repository":{"id":"%s","name":"qits-ci-service","backupUrl":%s,"lastBackup":null},
                   "declared":true}],
                 "wrapper":{"repositoryId":"%s","branch":"main","entries":[]}}"""
                .formatted(WRAPPER_ID, SERVICE_ID, serviceBackup, WRAPPER_ID);
    }

    private void theProjectAndItsRepositories(String serviceBackup) {
        server.scripts.add(c -> c.answer(PROJECTS));
        server.scripts.add(c -> c.answer(repositories(serviceBackup)));
    }

    private static String socket(String repoId) {
        return "/projects/api/repositories/" + repoId + "/remote-login";
    }

    @Test
    void withoutARepositoryItSignsInThroughTheProjectRepositoryAndAsksForTheBackupsAfterwards() throws Exception {
        FakeTerminal keyboard = new FakeTerminal();
        terminal = keyboard;
        theProjectAndItsRepositories("\"https://forge.example/qits/qits-ci-service.git\"");
        server.scripts.add(c -> c.answer(repositories("\"https://forge.example/qits/qits-ci-service.git\"")));
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("Username for 'https://forge.example': ");
            keyboard.type("me\r");
            c.awaitText();
            c.send("\r\n\u001b[33m[sign-in terminal exited (code 0)]\u001b[0m\r\n");
            c.close(1000, "");
        });
        server.scripts.add(c -> c.answer("{\"projectId\":\"%s\",\"scheduled\":2}".formatted(PROJECT_ID)));
        server.scripts.add(c -> c.answer(repositories("\"https://forge.example/qits/qits-ci-service.git\"")));

        Result signedIn = run("repositories", "remote-login", "--project", "qits");

        assertThat(signedIn.exit()).as(signedIn.err()).isZero();
        assertThat(server.upgrades).extracting(FakeSocketServer.Upgrade::path).containsExactly(
                "/projects/api/projects",
                "/projects/api/projects/" + PROJECT_ID + "/repositories",
                "/projects/api/projects/" + PROJECT_ID + "/repositories",
                socket(WRAPPER_ID),
                "/projects/api/projects/" + PROJECT_ID + "/repositories/backup-sync",
                "/projects/api/projects/" + PROJECT_ID + "/repositories");
        assertThat(server.upgrades.get(3).authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        FakeSocketServer.Connection login = server.connections.get(3);
        assertThat(JSON.readTree(login.texts.get(0))).isEqualTo(JSON.readTree("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));
        assertThat(JSON.readTree(login.texts.get(1))).isEqualTo(JSON.readTree("{\"type\":\"data\",\"data\":\"me\\r\"}"));
        assertThat(signedIn.out()).startsWith("Username for 'https://forge.example': ")
                .contains("[sign-in terminal exited (code 0)]")
                .contains("Asked for the backups of 2 repositories of project qits to run again.")
                .contains("qits-qits").contains("AUTH_REQUIRED").contains("qits-ci-service").contains("never")
                .contains("qits repositories --project qits list -o json");
        assertThat(signedIn.err()).contains("attached to the backup-remote sign-in of qits-qits; Ctrl-] detaches")
                .contains("the sign-in ended");
        assertThat(time.sleeps).as("one read, the panel's delay later").containsExactly(RemoteLoginCommand.REFRESH_DELAY);
        assertThat(keyboard.restored()).isTrue();
    }

    @Test
    void aRepositoryIsNamedByNameOrIdAndReadOnlyWithoutATerminal() {
        for (String named : new String[] {"qits-ci-service", SERVICE_ID}) {
            server.upgrades.clear();
            server.connections.clear();
            theProjectAndItsRepositories("\"https://forge.example/qits/qits-ci-service.git\"");
            server.scripts.add(c -> {
                c.accept();
                c.send("Password: ");
                c.close(1000, "");
            });
            server.scripts.add(c -> c.answer("{\"projectId\":\"%s\",\"scheduled\":2}".formatted(PROJECT_ID)));
            server.scripts.add(c -> c.answer(repositories("\"https://forge.example/qits/qits-ci-service.git\"")));

            Result signedIn = run("repositories", "--project", "qits", "remote-login", "--repository", named);

            assertThat(signedIn.exit()).as(signedIn.err()).isZero();
            assertThat(server.upgrades.get(2).path()).isEqualTo(socket(SERVICE_ID));
            assertThat(server.connections.get(2).texts).as("stdin is no terminal: nothing is sent").isEmpty();
            assertThat(signedIn.out()).startsWith("Password: ");
        }
    }

    @Test
    void ctrlBracketDetachesAndAsksForNoBackup() throws Exception {
        FakeTerminal keyboard = new FakeTerminal();
        terminal = keyboard;
        theProjectAndItsRepositories("null");
        server.scripts.add(c -> c.answer(repositories("null")));
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("Username: ");
            keyboard.type("\u001d");
            c.hold();
        });

        Result detached = run("repositories", "remote-login", "--project", "qits");

        assertThat(detached.exit()).as(detached.err()).isZero();
        assertThat(server.upgrades).hasSize(4);
        assertThat(server.upgrades.getLast().path()).isEqualTo(socket(WRAPPER_ID));
        assertThat(detached.err()).contains("detached; the sign-in keeps running")
                .contains("about a minute for you to attach again");
        assertThat(detached.out()).doesNotContain("Asked for the backups");
        assertThat(keyboard.restored()).isTrue();
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void aRepositoryWithoutABackupRemoteHasNothingToSignInTo() {
        theProjectAndItsRepositories("null");

        Result refused = run("repositories", "remote-login", "--project", "qits", "--repository", "qits-ci-service");

        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.err()).contains("qits-ci-service has no backup remote");
        assertThat(server.upgrades).hasSize(2);
    }

    @Test
    void aProjectWithoutAProjectRepositoryNeedsTheRepositoryNamed() {
        server.scripts.add(c -> c.answer(PROJECTS));
        server.scripts.add(c -> c.answer("{\"entries\":[],\"wrapper\":null}"));

        Result refused = run("repositories", "remote-login", "--project", "qits");

        assertThat(refused.exit()).isEqualTo(2);
        assertThat(refused.err()).contains("has no project repository").contains("--repository");
    }

    @Test
    void theMcpServiceSignsInToNothing() {
        String previous = System.getProperty(CliContext.MCP_SERVICE);
        System.setProperty(CliContext.MCP_SERVICE, "true");
        try {
            Result refused = run("repositories", "remote-login", "--project", "qits");

            assertThat(refused.exit()).isEqualTo(2);
            assertThat(refused.err()).contains("needs a terminal");
            assertThat(server.upgrades).as("nothing was asked").isEmpty();
        } finally {
            if (previous == null) {
                System.clearProperty(CliContext.MCP_SERVICE);
            } else {
                System.setProperty(CliContext.MCP_SERVICE, previous);
            }
        }
    }
}
