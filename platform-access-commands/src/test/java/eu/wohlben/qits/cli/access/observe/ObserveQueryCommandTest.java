package eu.wohlben.qits.cli.access.observe;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static eu.wohlben.qits.cli.access.observe.Frames.tree;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * `qits observe query` against the observability search door, {@code POST
 * /observability/api/telemetry/records/search}, on the fake platform.
 */
class ObserveQueryCommandTest {

    private static final Instant NOW = Instant.parse("2026-09-12T11:00:00Z");
    private static final String SEARCH = "/observability/api/telemetry/records/search";

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

    /** The service's answer: these frames, the cut, and how far back the buffer reaches. */
    private void answer(boolean truncated, String bufferedSince, String... frames) {
        platform.answer("POST", SEARCH, "{\"records\":[" + String.join(",", frames) + "],\"truncated\":" + truncated
                + ",\"bufferedSince\":" + (bufferedSince == null ? "null" : "\"" + bufferedSince + "\"") + "}");
    }

    private JsonNode sent() {
        return tree(platform.requests("POST", SEARCH).getFirst().body());
    }

    @Test
    void itSendsTheLiveFiltersAndTheWindowAndPrintsTheLiveLines() {
        answer(false, "2026-09-12T09:00:00Z", Frames.log("connection refused"), Frames.span("GET /ci/api/builds", "ERROR"));

        assertThat(run("observe", "query", "--filter", "kind=log level>=ERROR",
                "--filter", "service^=qits-ci attr.exception.type?", "--since", "2h")).isZero();

        assertThat(sent()).isEqualTo(tree("""
                {"subscribe":[
                   {"conditions":[{"field":"kind","op":"exact","value":"log"},
                                  {"field":"severity","op":"min","value":"ERROR"}]},
                   {"conditions":[{"field":"service","op":"prefix","value":"qits-ci"},
                                  {"field":"attribute","key":"exception.type","op":"exists","value":true}]}],
                 "since":"2026-09-12T09:00:00Z","until":"2026-09-12T11:00:00Z","limit":100,"source":null}
                """));
        assertThat(platform.requests("POST", SEARCH).getFirst().contentType()).startsWith("application/json");
        ZoneId zone = ZoneId.systemDefault();
        assertThat(out()).isEqualTo("window: 2026-09-12T09:00:00Z .. 2026-09-12T11:00:00Z\n"
                + RecordLine.render(tree(Frames.log("connection refused")), zone) + "\n"
                + RecordLine.render(tree(Frames.span("GET /ci/api/builds", "ERROR")), zone) + "\n");
        assertThat(err()).as("the window lies within the buffer").isEmpty();
    }

    @Test
    void theSourceAndTheLimitGoInTheBody() {
        answer(false, "2026-09-12T10:00:00Z");

        assertThat(run("observe", "query", "--filter", "*", "--source", "_service/qits-ci", "--limit", "7",
                "--since", "2026-09-12T10:30:00Z", "--until", "2026-09-12T10:45:00Z")).isZero();

        assertThat(sent()).isEqualTo(tree("""
                {"subscribe":[{"conditions":[]}],"since":"2026-09-12T10:30:00Z","until":"2026-09-12T10:45:00Z",
                 "limit":7,"source":"_service/qits-ci"}
                """));
        assertThat(out()).isEqualTo("window: 2026-09-12T10:30:00Z .. 2026-09-12T10:45:00Z\n");
    }

    @Test
    void aCutAnswerSaysSoOnItsLastLine() {
        answer(true, "2026-09-12T09:00:00Z", Frames.log("one"), Frames.log("two"));

        assertThat(run("observe", "query", "--filter", "kind=log", "--limit", "2", "--since", "1h")).isZero();

        assertThat(out().lines().toList()).hasSize(4).last().isEqualTo("… truncated: 2 shown, more in the window");
    }

    @Test
    void theJsonFormIsOneObjectWithTheFramesAndTheWindow() {
        answer(true, "2026-09-12T09:00:00Z", Frames.metric("http.server.duration", 42, "ms"));

        assertThat(run("observe", "query", "--filter", "kind=metric", "--since", "30m", "-o", "json")).isZero();

        assertThat(out().lines().count()).isEqualTo(1);
        assertThat(tree(out())).isEqualTo(tree("{\"records\":[" + Frames.metric("http.server.duration", 42, "ms")
                + "],\"truncated\":true,\"window\":{\"since\":\"2026-09-12T10:30:00Z\",\"until\":\"2026-09-12T11:00:00Z\"}}"));
    }

    @Test
    void aWindowBeforeTheBufferGetsANote() {
        answer(false, "2026-09-12T10:15:00Z");

        assertThat(run("observe", "query", "--filter", "kind=log", "--since", "2h")).isZero();

        assertThat(err()).isEqualTo("qits observe query: the buffer reaches back only to 2026-09-12T10:15:00Z, and the "
                + "window starts at 2026-09-12T09:00:00Z. Before 2026-09-12T10:15:00Z the service has forgotten, so an "
                + "empty answer there is not proof that nothing happened.\n");
        assertThat(out()).isEqualTo("window: 2026-09-12T09:00:00Z .. 2026-09-12T11:00:00Z\n");
    }

    @Test
    void anEmptyBufferGetsANoteToo() {
        answer(false, null);
        assertThat(run("observe", "query", "--filter", "*")).isZero();
        assertThat(err()).contains("the searched sources hold no records at all");
    }

    @Test
    void aWindowAtTheBuffersStartGetsNoNote() {
        answer(false, "2026-09-12T10:00:00Z");
        assertThat(run("observe", "query", "--filter", "*", "--since", "2026-09-12T10:00:00Z")).isZero();
        assertThat(err()).isEmpty();
    }

    @Test
    void aRefusedFilterIsAUsageError() {
        platform.answer("POST", SEARCH, 400, "{\"message\":\"unreadable filter: unknown field 'nope'\"}");

        assertThat(run("observe", "query", "--filter", "kind=log")).isEqualTo(2);

        assertThat(err()).contains("The observability service refused the search.")
                .contains("answered HTTP 400: unreadable filter: unknown field 'nope'");
        assertThat(out()).isEmpty();
    }

    @Test
    void aForbiddenSearchIsExitOne() {
        platform.answer("POST", SEARCH, 403, "{\"message\":\"no\"}");
        assertThat(run("observe", "query", "--filter", "kind=log")).isEqualTo(1);
        assertThat(err()).contains("HTTP 403");
    }

    @Test
    void usageErrorsSendNothing() {
        assertThat(run("observe", "query")).isEqualTo(2);
        assertThat(err()).contains("Missing required option: '--filter=<conditions>'");
        assertThat(run("observe", "query", "--filter", "kind!=log")).isEqualTo(2);
        assertThat(err()).contains("has an operator qits does not know");
        assertThat(run("observe", "query", "--filter", "*", "--until", "2d", "--since", "1h")).isEqualTo(2);
        assertThat(err()).contains("is after --until");
        assertThat(run("observe", "query", "--filter", "*", "--since", "1y")).isEqualTo(2);
        assertThat(err()).contains("--since: '1y' is neither an ISO-8601 instant");
        assertThat(run("observe", "query", "--filter", "*", "--limit", "0")).isEqualTo(2);
        assertThat(err()).contains("--limit must be between 1 and 1000, not 0.");
        assertThat(run("observe", "query", "--filter", "*", "-o", "yaml")).isEqualTo(2);
        assertThat(err()).contains("--output must be text or json, not 'yaml'.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void theQueryDoesNotNeedTheLiveCommandsFilter() {
        // picocli checks a parent's required options even when a subcommand is named: the live
        // --filter is required in execute, so the query is not asked for it.
        answer(false, "2026-09-12T09:00:00Z");
        assertThat(run("observe", "query", "--filter", "*")).isZero();
    }
}
