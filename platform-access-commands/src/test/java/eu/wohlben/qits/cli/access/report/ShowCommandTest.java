package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code qits ci report show} against a fake qits-ci, in the token home (QITS_TOKEN), like an agent on a runner. */
class ShowCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = "5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17";
    private static final String REPORTS = "/ci/api/runs/" + RUN + "/reports";

    private FakePlatform ci;

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        ci = new FakePlatform();
        ci.answer("GET", REPORTS, """
                {"runId":"%s","commitSha":"0123456789abcdef0123","releaseRequestId":"rr-1",
                 "baseline":{"version":"2026.1003.52637","runId":"base-run","releaseRequestId":"rr-0","tagSha":"abc"},
                 "reports":[
                   {"id":"rep-1","kind":"test-results","kindVersion":1,"stepIndex":2,
                    "highlights":[{"severity":"bad","text":"3 tests failed\\u001b[31m","metric":"tests.failed","value":3,"delta":null},
                                  {"severity":"info","text":"+2 tests vs 2026.1003.52637","metric":"tests.total","value":412,"delta":2}],
                    "baselineRunId":"base-run","baselineVersion":"2026.1003.52637","payloadBytes":1234,
                    "submittedAt":"2026-10-06T10:00:00Z"},
                   {"id":"rep-2","kind":"coverage","kindVersion":1,"stepIndex":2,"highlights":[],
                    "payloadBytes":10,"submittedAt":"2026-10-06T10:00:01Z"}]}
                """.formatted(RUN));
        ci.answer("GET", REPORTS + "/rep-1", """
                {"id":"rep-1","kind":"test-results","kindVersion":1,"stepIndex":2,"highlights":[],
                 "payload":{"totals":{"tests":412,"passed":409,"failed":2,"errored":1,"skipped":0,"durationMs":81234},
                            "suites":[],"failures":[{"coordinates":{"className":"eu.wohlben.qits.ci.api.FooTest",
                            "testName":"refusesAnotherRunsToken"},"shape":"ASSERTION","message":"expected: <403>"}],
                            "truncated":false}}
                """);
    }

    @AfterEach
    void stop() {
        ci.close();
    }

    @Test
    void theTableListsEachReportWithItsHighlights() {
        Result result = run("ci", "report", "show", RUN);

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("""
                Run %s, commit 0123456789ab, release request rr-1
                Baseline: 2026.1003.52637 (run base-run)
                KIND          VERSION  STEP  HIGHLIGHTS
                test-results  1        2     [bad] 3 tests failed
                                             [info] +2 tests vs 2026.1003.52637
                coverage      1        2     -
                """.formatted(RUN));
        assertThat(ci.requests.getFirst().authorization()).isEqualTo("Bearer agent-token");
    }

    @Test
    void aKindPrintsItsWholeReport() throws Exception {
        Result result = run("ci", "report", "show", RUN, "--kind", "test-results");

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).contains("test-results  1        2     [bad] 3 tests failed")
                .doesNotContain("coverage")
                .contains("test-results (version 1, step 2):")
                .contains("\"testName\" : \"refusesAnotherRunsToken\"");
        assertThat(ci.requests("GET", REPORTS + "/rep-1")).hasSize(1);
        assertThat(ci.requests("GET", REPORTS + "/rep-2")).isEmpty();
    }

    @Test
    void jsonIsTheServicesAnswer() throws Exception {
        Result all = run("ci", "report", "show", RUN, "-o", "json");
        Result kind = run("ci", "report", "show", RUN, "--kind", "test-results", "-o", "json");

        assertThat(JSON.readTree(all.out()).path("reports")).hasSize(2);
        JsonNode reports = JSON.readTree(kind.out());
        assertThat(reports.isArray()).isTrue();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0).path("payload").path("totals").path("tests").asInt()).isEqualTo(412);
    }

    @Test
    void aKindTheRunDoesNotHaveIsSaid() {
        Result result = run("ci", "report", "show", RUN, "--kind", "entity-changes");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("Run " + RUN + " has no entity-changes report.\n");
    }

    @Test
    void aRunWithoutReportsSaysSo() {
        String other = "bbbb2222-0000-4000-8000-000000000002";
        ci.answer("GET", "/ci/api/runs/" + other + "/reports", """
                {"runId":"%s","commitSha":"c0ffee","releaseRequestId":null,"baseline":null,"reports":[]}
                """.formatted(other));

        Result result = run("ci", "report", "show", other);

        assertThat(result.exit()).isZero();
        assertThat(result.out()).endsWith("Baseline: none\nNo reports.\n");
    }

    @Test
    void anUnknownRunIs404() {
        Result result = run("ci", "report", "show", "cccc3333-0000-4000-8000-000000000003");

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("No such run: cccc3333-0000-4000-8000-000000000003 (HTTP 404).");
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.of("QITS_TOKEN", "agent-token", "QITS_DOMAIN", "example.test",
                        "QITS_CI_URL", ci.url()),
                InputStream.nullInputStream(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.systemUTC(), Sleeper.real(), name -> null,
                stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
