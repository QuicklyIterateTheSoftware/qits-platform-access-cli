package eu.wohlben.qits.cli.access.observe;

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
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import static eu.wohlben.qits.cli.access.observe.Frames.T0;
import static eu.wohlben.qits.cli.access.observe.Frames.tree;
import static org.assertj.core.api.Assertions.assertThat;

/** The socket client against a WebSocket server in this process. */
class ObserveStreamTest {

    private static final String GAP = "records in the gap are missed (the stream has no replay)";
    private static final String FRAME = "{\"subscribe\":[{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"log\"}]}]}";
    private static final Duration HOUR = Duration.ofHours(1);

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeSocketServer server;
    private FakeTime time;
    private SessionFile store;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

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
        assertThat(out()).doesNotContain(FakeIdp.SECRET);
        assertThat(err()).doesNotContain(FakeIdp.SECRET);
        assertThat(err().lines()).allMatch(l -> l.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.* qits observe: .*"));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private ObserveStream stream(boolean json, Duration idleLimit, Duration pingEvery, PrintStream stdout) {
        return new ObserveStream(new PlatformClient(new AccessTokens(store, time, TokenClient::new)), server.socketUri(), FRAME,
                json, ZoneOffset.UTC, stdout, new PrintStream(err, true, StandardCharsets.UTF_8), time, time, idleLimit, pingEvery);
    }

    private ObserveStream stream(boolean json, Duration idleLimit, Duration pingEvery) {
        return stream(json, idleLimit, pingEvery, new PrintStream(out, true, StandardCharsets.UTF_8));
    }

    private ObserveStream stream() {
        return stream(false, ObserveStream.IDLE_LIMIT, ObserveStream.PING_EVERY);
    }

    private static CompletableFuture<Integer> runInBackground(ObserveStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return stream.run();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Runs the stream to its end, which must come within 20 s. */
    private static int runToEnd(ObserveStream stream) throws Exception {
        CompletableFuture<Integer> run = runInBackground(stream);
        try {
            return run.get(20, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            stream.stop();
            throw new AssertionError("the stream did not end within 20 s", e);
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

    @Test
    void theFiltersGoOutFirstAndEachRecordIsOneLine() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("connection refused"));
            c.send(Frames.span("GET /ci/api/builds", "ERROR"));
            c.send(Frames.metric("http.server.active", 3, ""));
            c.hold();
        });
        ObserveStream stream = stream();
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 3);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(out().lines()).containsExactly(
                "10:00:01.000 log    qits-ci ERROR connection refused  [4bf92f35]",
                "10:00:01.000 span   qits-ci ERROR GET /ci/api/builds  [4bf92f35]",
                "10:00:01.000 metric qits-ci 3     http.server.active");
        FakeSocketServer.Upgrade upgrade = server.upgrades.getFirst();
        assertThat(upgrade.path()).isEqualTo("/observability/stream");
        assertThat(upgrade.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-1");
        assertThat(server.connections.getFirst().texts).containsExactly(FRAME);
        assertThat(err()).contains("connected to " + server.socketUri()).contains("stopped").doesNotContain("reconnecting");
    }

    @Test
    void jsonOutputIsEachFrameOnItsOwnLine() throws Exception {
        List<String> frames = List.of(Frames.log("a \u001b[31mred\u001b[0m line\nand more"), Frames.span("GET /", "OK"),
                Frames.metric("m", 1.5, "s"));
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            for (String frame : frames) {
                c.send(frame);
            }
            c.hold();
        });
        ObserveStream stream = stream(true, ObserveStream.IDLE_LIMIT, ObserveStream.PING_EVERY);
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 3);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        List<String> lines = out().lines().toList();
        for (int i = 0; i < frames.size(); i++) {
            assertThat(tree(lines.get(i))).isEqualTo(tree(frames.get(i)));
        }
        assertThat(lines.getFirst()).contains("\\u001B[31mred").doesNotContain("\u001b");
    }

    @Test
    void noticesGoToStderrAndTheStreamGoesOn() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("one"));
            c.send("{\"dropped\":12}");
            c.send("{\"dropped\":5}");
            c.send("{\"error\":\"a \\u001b]0;title\\u0007notice\"}");
            c.send("{\"subscribed\":1}");
            c.send("not json");
            c.send(Frames.log("two"));
            c.hold();
        });
        ObserveStream stream = stream();
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 2);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(out()).contains("one").contains("two").doesNotContain("dropped").doesNotContain("notice");
        assertThat(err()).contains("the server dropped 12 records, because this side did not read them fast enough (12 in all)")
                .contains("the server dropped 5 records, because this side did not read them fast enough (17 in all)")
                .contains("the server says: a notice")
                .contains("the server sent a frame that is not a JSON object; it is skipped")
                .doesNotContain("subscribed")
                .doesNotContain("\u001b");
    }

    @Test
    void anErrorBeforeTheFirstRecordRefusesTheFilters() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send("{\"error\":\"unknown field \\u001b[31mfoo\"}");
            c.hold();
        });

        int exit = runToEnd(stream());

        assertThat(exit).isEqualTo(2);
        assertThat(err()).contains("The observability service refused the filters: unknown field foo")
                .doesNotContain("\u001b").doesNotContain("reconnecting");
        assertThat(time.sleeps).isEmpty();
    }

    @Test
    void aClosedOrDroppedSocketIsOpenedAgainAndTheFiltersGoOutAgain() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("one"));
            c.close(1001, "going away");
        });
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("two"));
            Thread.sleep(200);
            c.drop();
        });
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("three"));
            c.hold();
        });
        ObserveStream stream = stream();
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 3);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(out().lines()).extracting(l -> l.substring(l.indexOf("ERROR ") + 6, l.indexOf("  [")))
                .containsExactly("one", "two", "three");
        assertThat(server.connections).hasSize(3).allSatisfy(c -> assertThat(c.texts).containsExactly(FRAME));
        assertThat(err()).contains("the server closed the socket (code 1001: going away); reconnecting in 1 s — " + GAP)
                .contains("connected again to");
        assertThat(err().lines().filter(l -> l.contains("reconnecting in 1 s — " + GAP))).hasSize(2);
        assertThat(time.sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(1));
    }

    @Test
    void a5xxOnTheUpgradeIsTriedAgainWithAGrowingWait() throws Exception {
        for (int i = 0; i < 7; i++) {
            server.scripts.add(c -> c.refuse(503, "{\"message\":\"starting\"}"));
        }
        server.scripts.add(c -> c.refuse(403, "{\"message\":\"forbidden\"}"));

        int exit = runToEnd(stream());

        assertThat(exit).isEqualTo(1);
        assertThat(time.sleeps).extracting(Duration::toSeconds).containsExactly(1L, 2L, 4L, 8L, 16L, 30L, 30L);
        assertThat(err()).contains("GET " + server.socketUri() + " answered HTTP 503: starting; reconnecting in 1 s")
                .doesNotContain(GAP)
                .contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void a401OnTheUpgradeStopsWithTheChallenge() throws Exception {
        server.scripts.add(c -> c.refuse(401, "", "WWW-Authenticate",
                "Bearer error=\"invalid_token\", error_description=\"the token has expired\""));

        assertThat(runToEnd(stream())).isEqualTo(1);

        assertThat(err()).contains("The platform refused the token (HTTP 401: invalid_token, the token has expired)");
        assertThat(time.sleeps).isEmpty();
    }

    /** A socket that goes quiet (a suspend can leave one half open) is ended and opened again. */
    @Test
    void aSilentSocketIsOpenedAgain() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.holdSilent();
        });
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("after"));
            c.hold();
        });
        ObserveStream stream = stream(false, Duration.ofMillis(300), Duration.ofMillis(100));
        CompletableFuture<Integer> run = runInBackground(stream);

        await(() -> out().lines().count() == 1);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("the socket sent nothing, not even a keepalive, for 300 ms; reconnecting in 1 s — " + GAP);
    }

    @Test
    void pongsKeepAQuietSocketOpen() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.hold();
        });
        ObserveStream stream = stream(false, Duration.ofMillis(600), Duration.ofMillis(100));
        CompletableFuture<Integer> run = runInBackground(stream);
        await(() -> err().contains("connected to"));

        Thread.sleep(1500);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).doesNotContain("reconnecting");
        assertThat(server.connections).hasSize(1);
        assertThat(server.connections.getFirst().pings.get()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void theServersPingsKeepAQuietSocketOpenToo() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            for (int i = 0; i < 15; i++) {
                c.ping();
                Thread.sleep(100);
            }
            c.hold();
        });
        ObserveStream stream = stream(false, Duration.ofMillis(600), HOUR);
        CompletableFuture<Integer> run = runInBackground(stream);
        await(() -> err().contains("connected to"));

        Thread.sleep(1400);
        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).doesNotContain("reconnecting");
        assertThat(server.connections.getFirst().pings.get()).isZero();
    }

    @Test
    void theTokenIsRefreshedBeforeEachConnectionThatNeedsIt() throws Exception {
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-old", T0.plusSeconds(10),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.close(1008, "the token has expired");
        });
        server.scripts.add(c -> c.refuse(403, ""));
        // The access token has run out by the time the stream reconnects.
        time.afterSleep = () -> time.jump(Duration.ofMinutes(20));

        assertThat(runToEnd(stream())).isEqualTo(1);

        assertThat(idp.grants("refresh_token")).isEqualTo(2);
        String first = server.upgrades.get(0).authorization();
        String second = server.upgrades.get(1).authorization();
        assertThat(first).isNotEqualTo("Bearer " + FakeIdp.SECRET + "access-old");
        assertThat(second).isNotEqualTo(first).isEqualTo("Bearer " + store.read().orElseThrow().accessToken());
        assertThat(err()).contains("the server closed the socket (code 1008: the token has expired); reconnecting in 1 s");
    }

    @Test
    void aStopEndsABlockedRead() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.hold();
        });
        ObserveStream stream = stream();
        CompletableFuture<Integer> run = runInBackground(stream);
        await(() -> err().contains("connected to"));

        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("stopped").doesNotContain("reconnecting");
    }

    @Test
    void aStopEndsAHandshakeThatHangs() throws Exception {
        server.scripts.add(FakeSocketServer.Connection::holdSilent);
        ObserveStream stream = stream();
        CompletableFuture<Integer> run = runInBackground(stream);
        await(() -> !server.upgrades.isEmpty());

        stream.stop();

        assertThat(run.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(err()).contains("stopped").doesNotContain("reconnecting").doesNotContain("connected to");
    }

    @Test
    void aClosedStdoutStopsTheStream() throws Exception {
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("nobody reads this"));
            c.hold();
        });
        PrintStream closed = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("Broken pipe");
            }
        }, true, StandardCharsets.UTF_8);

        assertThat(runToEnd(stream(false, ObserveStream.IDLE_LIMIT, ObserveStream.PING_EVERY, closed))).isZero();

        assertThat(err()).contains("stdout is closed; stopped").doesNotContain("reconnecting");
    }
}
