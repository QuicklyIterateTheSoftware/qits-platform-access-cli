package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeSocketServer;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static eu.wohlben.qits.cli.access.observe.Frames.T0;
import static eu.wohlben.qits.cli.access.observe.Frames.tree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** `qits observe` as a person types it, in this process. */
class ObserveCommandTest {

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeSocketServer server;
    private SessionFile store;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final AtomicReference<Runnable> stop = new AtomicReference<>();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        server = new FakeSocketServer();
        store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", T0.plusSeconds(900),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_OBSERVABILITY_URL", server.url());
    }

    @AfterEach
    void stop() {
        server.close();
        idp.close();
        assertThat(out()).doesNotContain(FakeIdp.SECRET);
        assertThat(err()).doesNotContain(FakeIdp.SECRET);
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private int run(String... args) {
        FakeTime time = new FakeTime(T0);
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop::set);
        return TestCli.execute(context, args);
    }

    @Test
    void theFilterIsRequired() {
        assertThat(run("observe")).isEqualTo(2);
        assertThat(err()).contains("--filter");
        assertThat(server.upgrades).isEmpty();
    }

    @Test
    void aBadConditionStopsBeforeAnythingGoesOut() {
        assertThat(run("observe", "--filter", "kind=log", "--filter", "service=a kind!=log")).isEqualTo(2);
        assertThat(err()).contains("--filter: 'kind!=log' has an operator qits does not know. Write F=V, F^=V, F~V, F?, !F or level>=V.");
        assertThat(server.upgrades).isEmpty();
    }

    @Test
    void aBadOutputIsAUsageError() {
        assertThat(run("observe", "--filter", "*", "-o", "yaml")).isEqualTo(2);
        assertThat(err()).contains("--output must be text or json, not 'yaml'.");
    }

    @Test
    void withoutASessionItSaysToSignIn() throws Exception {
        Files.delete(store.path());
        assertThat(run("observe", "--filter", "*")).isEqualTo(2);
        assertThat(err()).contains("Not signed in — run `qits login`.");
    }

    @Test
    void theAddressComesFromTheIdpWhenNothingNamesIt() {
        env.remove("QITS_OBSERVABILITY_URL");
        assertThat(run("observe", "--filter", "*")).isEqualTo(2);
        assertThat(err()).contains("Pass --observability-url or set QITS_OBSERVABILITY_URL.");
    }

    @Test
    void theCommandSendsItsFiltersAndPrintsTheRecords() throws Exception {
        env.remove("QITS_OBSERVABILITY_URL");
        server.scripts.add(c -> {
            c.accept();
            c.awaitText();
            c.send(Frames.log("connection refused"));
            c.hold();
        });

        CompletableFuture<Integer> exit = CompletableFuture.supplyAsync(() -> run("observe",
                "--filter", "kind=log level>=ERROR",
                "--filter", "trace=4bf92f3577b34da6a3ce929d0e0e4736",
                "--filter", "service^=qits-ci attr.exception.type?",
                "--observability-url", server.url() + "/", "-o", "json"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (out().lines().count() < 1 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        stop.get().run();

        assertThat(exit.get(10, TimeUnit.SECONDS)).isZero();
        assertThat(tree(out().strip())).isEqualTo(tree(Frames.log("connection refused")));
        assertThat(server.upgrades.getFirst().path()).isEqualTo("/observability/stream");
        assertThat(server.connections.getFirst().texts).containsExactly("{\"subscribe\":["
                + "{\"conditions\":[{\"field\":\"kind\",\"op\":\"exact\",\"value\":\"log\"},"
                + "{\"field\":\"severity\",\"op\":\"min\",\"value\":\"ERROR\"}]},"
                + "{\"conditions\":[{\"field\":\"traceId\",\"op\":\"exact\",\"value\":\"4bf92f3577b34da6a3ce929d0e0e4736\"}]},"
                + "{\"conditions\":[{\"field\":\"service\",\"op\":\"prefix\",\"value\":\"qits-ci\"},"
                + "{\"field\":\"attribute\",\"key\":\"exception.type\",\"op\":\"exists\",\"value\":true}]}]}");
        assertThat(err()).contains("qits observe: connected to ws://127.0.0.1:" + server.port() + "/observability/stream")
                .contains("qits observe: stopped");
    }

    @Test
    void theSocketAddressFollowsTheScheme() throws Exception {
        assertThat(ObserveCommand.socketUri("https://observability.dev.wohlben.eu"))
                .hasToString("wss://observability.dev.wohlben.eu/observability/stream");
        assertThat(ObserveCommand.socketUri("http://observability.prod.localhost:8080"))
                .hasToString("ws://observability.prod.localhost:8080/observability/stream");
        assertThat(ObserveCommand.socketUri("HTTPS://o.example")).hasToString("wss://o.example/observability/stream");
        for (String bad : new String[] {"ftp://o.example", "o.example", "https://", "https://a b"}) {
            assertThatThrownBy(() -> ObserveCommand.socketUri(bad)).as(bad)
                    .isInstanceOf(CliFailure.class).hasMessageContaining("is not a usable observability address");
        }
    }
}
