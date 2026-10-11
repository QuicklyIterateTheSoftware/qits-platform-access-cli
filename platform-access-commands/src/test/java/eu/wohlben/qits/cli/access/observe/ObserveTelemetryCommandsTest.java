package eu.wohlben.qits.cli.access.observe;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** `qits observe sources|errors|slow-spans|metrics` against the telemetry read doors on the fake platform. */
class ObserveTelemetryCommandsTest {

    private static final Instant NOW = Instant.parse("2026-10-10T11:00:00Z");
    private static final String TELEMETRY = "/observability/api/telemetry";
    private static final char ESC = 0x1B;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        SessionFile store = new SessionFile(home.resolve("qits"));
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-1", NOW.plusSeconds(900),
                idp.issueRefreshToken(), NOW.plus(Duration.ofDays(30))));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_OBSERVABILITY_URL", platform.url());
    }

    @AfterEach
    void stop() {
        platform.close();
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
        FakeTime time = new FakeTime(NOW);
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, r -> { });
        return TestCli.execute(context, args);
    }

    private String query(String path) {
        return platform.requests("GET", TELEMETRY + path).getFirst().query();
    }

    @Test
    void sourcesListsTheBucketKeys() {
        platform.answer("GET", TELEMETRY + "/sources", """
                {"sources":[{"key":"_service/qits-ci","kind":"SERVICE","label":"qits-ci","spans":4,"logs":9,
                  "metricSeries":2}]}
                """);

        assertThat(run("observe", "sources")).as(err()).isZero();
        assertThat(out()).contains("_service/qits-ci").contains("SERVICE");
    }

    @Test
    void errorsNamesTheBucketBySourceAndPrintsEachTrace() {
        platform.answer("GET", TELEMETRY + "/errors", """
                {"groups":[{"traceId":"t1","serviceName":"qits-ci",
                  "errorSpans":[{"name":"GET /x","statusMessage":"boom\\u001b[2J"}],"errorLogs":[]}],
                 "total":3,"truncated":true}
                """);

        assertThat(run("observe", "errors", "--source", "_service/qits-ci", "--since-minutes", "30")).as(err())
                .isZero();
        assertThat(query("/errors")).isEqualTo("source=_service%2Fqits-ci&sinceMinutes=30");
        assertThat(out()).contains("t1").contains("GET /x: boom").contains("Showing 1 of 3")
                .doesNotContain(String.valueOf(ESC));
    }

    @Test
    void slowSpansSendsTheThresholdAndAWorkspacePair() {
        platform.answer("GET", TELEMETRY + "/slow-spans", """
                {"spans":[{"traceId":"t2","serviceName":"app","name":"SELECT","durationMs":812,"status":"OK"}],
                 "total":1,"truncated":false}
                """);

        assertThat(run("observe", "slow-spans", "--repository-id", "r1", "--workspace-id", "w1",
                "--threshold-ms", "800")).as(err()).isZero();
        assertThat(query("/slow-spans")).isEqualTo("repositoryId=r1&workspaceId=w1&thresholdMs=800");
        assertThat(out()).contains("812 ms").contains("SELECT");
    }

    @Test
    void metricsNarrowsByNameAndPrintsAsJson() {
        platform.answer("GET", TELEMETRY + "/metrics", """
                {"metrics":[{"name":"jvm.threads","value":12.0,"unit":"1","serviceName":"app","attributes":{}}]}
                """);

        assertThat(run("observe", "metrics", "--source", "s", "--name", "jvm.threads", "-o", "json")).as(err())
                .isZero();
        assertThat(query("/metrics")).isEqualTo("source=s&name=jvm.threads");
        assertThat(Frames.tree(out()).path("metrics").get(0).path("value").asDouble()).isEqualTo(12.0);
    }

    @Test
    void aReadWithNoBucketSendsNothing() {
        assertThat(run("observe", "errors")).isEqualTo(2);
        assertThat(err()).contains("Name the bucket");
        assertThat(run("observe", "metrics", "--workspace-id", "w1")).isEqualTo(2);
        assertThat(err()).contains("go together");
        assertThat(platform.requests).isEmpty();
    }
}
