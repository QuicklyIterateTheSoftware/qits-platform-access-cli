package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeSocketServer;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.AccessTokens;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The terminal attachment against a WebSocket server in this process, with a fake local terminal:
 * what goes out (the size, the keys), what comes back, and how each kind of ending ends it — always
 * with the terminal as it was.
 */
class TerminalAttachTest {

    private static final Instant T0 = Instant.parse("2026-10-16T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COMMAND = "00000000-0000-4000-8000-000000000100";
    private static final String PATH = "/workspaces/container/1/terminal/commands/" + COMMAND;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeSocketServer server;
    private FakeTime time;
    private SessionFile store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    /** A terminal that records what was done to it; the test types and resizes. */
    static final class FakeTerminal implements LocalTerminal {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final PipedOutputStream typing = new PipedOutputStream();
        private final PipedInputStream keys;
        volatile Size size = new Size(120, 40);
        volatile Runnable resized;

        FakeTerminal() throws IOException {
            keys = new PipedInputStream(typing);
        }

        void type(String text) throws IOException {
            typing.write(text.getBytes(StandardCharsets.UTF_8));
            typing.flush();
        }

        void resize(int cols, int rows) {
            size = new Size(cols, rows);
            resized.run();
        }

        boolean restored() {
            return calls.equals(List.of("raw", "restore"));
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        public void enterRaw() {
            calls.add("raw");
        }

        @Override
        public void restore() {
            calls.add("restore");
        }

        @Override
        public Size size() {
            return size;
        }

        @Override
        public boolean onResize(Runnable resized) {
            this.resized = resized;
            return true;
        }

        @Override
        public InputStream keys() {
            return keys;
        }
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        server = new FakeSocketServer();
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", T0.plusSeconds(900),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        server.close();
        idp.close();
        assertThat(out() + err()).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private TerminalAttach attach(LocalTerminal terminal, Duration sizePoll) {
        return new TerminalAttach(new PlatformClient(new AccessTokens(store, time, TokenClient::new)),
                URI.create("ws://127.0.0.1:" + server.port() + PATH), COMMAND, terminal,
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, TerminalAttach.BACKOFF, sizePoll);
    }

    private TerminalAttach attach(LocalTerminal terminal) {
        return attach(terminal, Duration.ofHours(1));
    }

    private static CompletableFuture<Integer> runInBackground(TerminalAttach attachment) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return attachment.run();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static int runToEnd(TerminalAttach attachment) throws Exception {
        CompletableFuture<Integer> run = runInBackground(attachment);
        try {
            return run.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            attachment.stop();
            throw new AssertionError("the attachment did not end within 20 s", e);
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the condition did not hold within 10 s; stderr: " + err());
            }
            Thread.sleep(20);
        }
    }

    private static JsonNode json(String text) throws IOException {
        return JSON.readTree(text);
    }

    @Test
    void theFramesAreTheLandingAppsShapes() throws Exception {
        assertThat(json(TerminalAttach.dataFrame("ls -l\r"))).isEqualTo(json("{\"type\":\"data\",\"data\":\"ls -l\\r\"}"));
        assertThat(json(TerminalAttach.dataFrame("\u0003"))).isEqualTo(json("{\"type\":\"data\",\"data\":\"\\u0003\"}"));
        assertThat(json(TerminalAttach.resizeFrame(new LocalTerminal.Size(120, 40)).json()))
                .isEqualTo(json("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));
    }

    @Test
    void theSizeGoesOutOnOpenTheKeysAsTheyAreTypedAndCtrlBracketDetaches() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        CompletableFuture<String> firstFrame = new CompletableFuture<>();
        CompletableFuture<String> typed = new CompletableFuture<>();
        server.scripts.add(c -> {
            c.accept();
            firstFrame.complete(c.awaitText());
            c.send("$ ");
            typed.complete(c.awaitText());
            c.hold();
        });
        TerminalAttach attachment = attach(terminal);
        CompletableFuture<Integer> run = runInBackground(attachment);

        assertThat(json(firstFrame.get(10, TimeUnit.SECONDS)))
                .isEqualTo(json("{\"type\":\"resize\",\"cols\":120,\"rows\":40}"));
        await(() -> out().equals("$ "));
        terminal.type("ls\r");
        assertThat(json(typed.get(10, TimeUnit.SECONDS))).isEqualTo(json("{\"type\":\"data\",\"data\":\"ls\\r\"}"));
        terminal.type("\u001d");

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("detached; the command keeps running");
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
        assertThat(server.upgrades).singleElement().satisfies(upgrade -> {
            assertThat(upgrade.path()).isEqualTo(PATH);
            assertThat(upgrade.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-1");
        });
        assertThat(server.connections.getFirst().texts).hasSize(2);
    }

    @Test
    void keysBeforeCtrlBracketInTheSameReadStillGoOut() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.hold();
        });
        TerminalAttach attachment = attach(terminal);
        CompletableFuture<Integer> run = runInBackground(attachment);
        await(() -> !server.connections.isEmpty() && server.connections.getFirst().texts.size() == 1);

        terminal.type("q\u001d");

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        await(() -> server.connections.getFirst().texts.size() == 2);
        assertThat(json(server.connections.getFirst().texts.get(1)))
                .isEqualTo(json("{\"type\":\"data\",\"data\":\"q\"}"));
    }

    @Test
    void aWindowChangeSendsTheNewSize() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        CompletableFuture<String> second = new CompletableFuture<>();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            second.complete(c.awaitText());
            c.hold();
        });
        TerminalAttach attachment = attach(terminal);
        CompletableFuture<Integer> run = runInBackground(attachment);
        await(() -> !server.connections.isEmpty() && server.connections.getFirst().texts.size() == 1);

        terminal.resize(80, 24);

        assertThat(json(second.get(10, TimeUnit.SECONDS))).isEqualTo(json("{\"type\":\"resize\",\"cols\":80,\"rows\":24}"));
        attachment.stop();
        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void theSizeIsAlsoPolledSoAMissedSignalCannotKeepTheOldSize() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        CompletableFuture<String> second = new CompletableFuture<>();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            second.complete(c.awaitText());
            c.hold();
        });
        TerminalAttach attachment = attach(terminal, Duration.ofMillis(50));
        CompletableFuture<Integer> run = runInBackground(attachment);
        await(() -> !server.connections.isEmpty() && server.connections.getFirst().texts.size() == 1);

        terminal.size = new LocalTerminal.Size(132, 50);

        assertThat(json(second.get(10, TimeUnit.SECONDS)))
                .isEqualTo(json("{\"type\":\"resize\",\"cols\":132,\"rows\":50}"));
        Thread.sleep(200);
        assertThat(server.connections.getFirst().texts).as("an unchanged size is not sent again").hasSize(2);
        attachment.stop();
        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
    }

    @Test
    void aCleanCloseIsTheCommandEndingAndEndsWithZero() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("Login successful.\r\n");
            c.close(1000, "");
        });

        assertThat(runToEnd(attach(terminal))).isZero();

        assertThat(out()).isEqualTo("Login successful.\r\n");
        assertThat(err()).contains("the command ended");
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
        assertThat(server.upgrades).hasSize(1);
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void aMessageInPartsIsWrittenWhole() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.sendInParts("Paste the ", "code here: ", "\u001b[1m>\u001b[0m");
            c.close(1000, "");
        });

        assertThat(runToEnd(attach(LocalTerminal.READ_ONLY))).isZero();

        assertThat(out()).isEqualTo("Paste the code here: \u001b[1m>\u001b[0m");
    }

    @Test
    void anyOtherCloseAttachesAgainClearsTheScreenAndSendsTheSizeAgain() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("before");
            c.close(1011, "the daemon restarts");
        });
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("replayed");
            c.close(1000, "");
        });

        assertThat(runToEnd(attach(terminal))).isZero();

        assertThat(out()).isEqualTo("before" + TerminalAttach.CLEAR + "replayed");
        assertThat(time.sleeps).containsExactly(Duration.ofMillis(500));
        assertThat(err()).contains("code 1011: the daemon restarts").contains("attaching again in 0.5 s");
        assertThat(server.connections).hasSize(2).allSatisfy(connection ->
                assertThat(connection.texts.getFirst()).contains("\"type\":\"resize\""));
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
    }

    @Test
    void aDroppedConnectionGivesUpAfterTheLandingAppsFiveWaits() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        for (int i = 0; i < 6; i++) {
            server.scripts.add(c -> c.refuse(503, "{\"message\":\"the daemon is not connected\"}"));
        }

        assertThat(runToEnd(attach(terminal))).isEqualTo(1);

        assertThat(time.sleeps).containsExactlyElementsOf(TerminalAttach.BACKOFF);
        assertThat(server.upgrades).hasSize(6);
        assertThat(err()).contains("gave up after 5 attempts");
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
    }

    @Test
    void theWaitsStartOverOnceAConnectionOpens() throws Exception {
        server.scripts.add(c -> c.refuse(503, "{}"));
        server.scripts.add(c -> {
            c.accept();
            c.drop();
        });
        server.scripts.add(c -> {
            c.accept();
            c.close(1000, "");
        });

        assertThat(runToEnd(attach(LocalTerminal.READ_ONLY))).isZero();

        assertThat(time.sleeps).containsExactly(Duration.ofMillis(500), Duration.ofMillis(500));
    }

    @Test
    void aRefusedSocketEndsAtOnceAndRestoresTheTerminal() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        server.scripts.add(c -> c.refuse(404, "{\"message\":\"no such slot\"}"));

        assertThat(runToEnd(attach(terminal))).isEqualTo(1);

        assertThat(server.upgrades).hasSize(1);
        assertThat(time.sleeps).isEmpty();
        assertThat(err()).contains("404");
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
    }

    @Test
    void withoutATerminalItOnlyStreamsAndSendsNothing() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.send("Visit https://example.invalid/device and enter ABCD-EFGH\r\n");
            c.ping();
            c.send("Signed in.\r\n");
            c.close(1000, "");
        });

        assertThat(runToEnd(attach(LocalTerminal.READ_ONLY))).isZero();

        assertThat(out()).isEqualTo("Visit https://example.invalid/device and enter ABCD-EFGH\r\nSigned in.\r\n");
        assertThat(server.connections.getFirst().texts).as("no size and no keys from a pipe").isEmpty();
        assertThat(err()).doesNotContain("Ctrl-]");
    }

    @Test
    void aStopLeavesTheCommandRunningAndRestoresTheTerminal() throws Exception {
        FakeTerminal terminal = new FakeTerminal();
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.hold();
        });
        TerminalAttach attachment = attach(terminal);
        CompletableFuture<Integer> run = runInBackground(attachment);
        await(() -> !server.connections.isEmpty() && server.connections.getFirst().texts.size() == 1);

        attachment.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("stopped; the command keeps running");
        assertThat(terminal.restored()).as(terminal.calls.toString()).isTrue();
    }

    @Test
    void aStopDuringTheWaitEndsWithoutAnotherAttempt() throws Exception {
        server.scripts.add(c -> c.refuse(503, "{}"));
        TerminalAttach attachment = attach(LocalTerminal.READ_ONLY);
        time.afterSleep = attachment::stop;

        assertThat(runToEnd(attachment)).isZero();

        assertThat(server.upgrades).hasSize(1);
    }
}
