package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits ci report submit} as the hook runs it in a QA step: the step's environment, the fixture
 * tree, and qits-ci as a local HTTP stub that records what it was asked.
 */
class SubmitCommandTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String RUN = "5f2c0a9e-1b7d-4c2e-9a41-3d8e6f0b2c17";
    private static final String BASE_RUN = "aaaa1111-0000-4000-8000-000000000001";
    private static final String TOKEN = "ci-run-token-of-this-run";
    private static final String BASELINE = "/ci/api/runs/" + RUN + "/baseline";
    private static final String BASELINE_REPORTS = BASELINE + "/reports/test-results";
    private static final String PUT = "/ci/api/runs/" + RUN + "/steps/2/reports/test-results";

    @TempDir
    Path work;

    private FakePlatform ci;
    private String origin;
    private Duration deadline = SubmitCommand.DEADLINE;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        ci = new FakePlatform();
        origin = ci.url();
        env.put("QITS_CI_RUN_ID", RUN);
        env.put("QITS_CI_STEP_INDEX", "2");
        env.put("QITS_CI_SHA", Fixtures.COMMIT);
        env.put("QITS_CI_REPO_NAME", Fixtures.REPOSITORY.name());
        env.put("QITS_CI_PROJECT_ID", Fixtures.REPOSITORY.projectId());
        env.put("QITS_CI_REPOSITORY_URL", "http://githost.invalid/qits-fx-service.git");
        env.put("QITS_PUBLISH_TOKEN", TOKEN);
    }

    @AfterEach
    void stop() {
        ci.close();
    }

    @Test
    void theReportGoesToTheRunsStepWithItsHighlightsAndItsBaseline() throws Exception {
        Path root = Fixtures.tree(work);
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":"rr-1","tagSha":"abc"}}
                """.formatted(BASE_RUN));
        ci.answer("GET", BASELINE_REPORTS, """
                [{"id":"r-0","kind":"test-results","kindVersion":1,"stepIndex":1,"highlights":[],
                  "payload":{"totals":{"tests":99,"passed":99,"failed":0,"errored":0,"skipped":0,"durationMs":1},
                             "suites":[],"failures":[],"truncated":false}},
                 {"id":"r-1","kind":"test-results","kindVersion":1,"stepIndex":2,"highlights":[],
                  "payload":{"totals":{"tests":15,"passed":15,"failed":0,"errored":0,"skipped":0,"durationMs":1},
                             "suites":[],"failures":[],"truncated":false,"aFieldFromLater":true}}]
                """);
        ci.answer("PUT", PUT, 204, "");

        Result result = submit("--exit-code", "1", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("test-results: submitted (18 tests, 10 failed)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(result.err()).contains("baseline: 2026.1003.52637 (run " + BASE_RUN + ")");
        assertThat(ci.requests).extracting(r -> r.method() + " " + r.path())
                .containsExactly("GET " + BASELINE, "GET " + BASELINE_REPORTS, "PUT " + PUT);
        assertThat(ci.requests).allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer " + TOKEN));

        FakePlatform.Request put = ci.requests("PUT", PUT).getFirst();
        assertThat(put.contentType()).isEqualTo("application/json");
        JsonNode body = JSON.readTree(put.body());
        assertThat(body.fieldNames()).toIterable().containsExactly("kindVersion", "highlights", "baseline", "payload");
        assertThat(body.path("kindVersion").asInt()).isEqualTo(1);
        assertThat(body.path("baseline")).isEqualTo(JSON.readTree("""
                {"runId":"%s","version":"2026.1003.52637"}""".formatted(BASE_RUN)));
        assertThat(body.path("highlights")).isEqualTo(JSON.readTree("""
                [{"severity":"bad","text":"10 tests failed","metric":"tests.failed","value":10.0,"delta":null},
                 {"severity":"info","text":"+3 tests vs 2026.1003.52637","metric":"tests.total","value":18.0,"delta":3.0}]
                """));
        TestResults expected = new TestResultsKind(line -> { }).collect(
                new StepContext(root, RUN, 2, Fixtures.REPOSITORY, Fixtures.COMMIT, 1, Optional.empty(),
                        ChangedLines.unavailable(), TestCaseLocators.registered()),
                ReportKinds.standard(line -> { }).parsersOf("test-results")).orElseThrow();
        assertThat(body.path("payload")).isEqualTo(JSON.readTree(ReportJson.MAPPER.writeValueAsString(expected)));
    }

    @Test
    void coverageGoesWithItsTotalItsBaselinesTotalAndTheDiffAgainstTheBaselineTag() throws Exception {
        Path root = Fixtures.coverageTree(work);
        String clock = Files.readString(root.resolve("src/app/clock.ts"));
        Files.delete(root.resolve("src/app/clock.ts"));
        GitChangedLinesTest.git(root, "init", "--quiet");
        GitChangedLinesTest.git(root, "add", "-A");
        GitChangedLinesTest.git(root, "commit", "--quiet", "-m", "the baseline");
        GitChangedLinesTest.git(root, "tag", "2026.1003.52637");
        Files.writeString(root.resolve("src/app/clock.ts"), clock);
        GitChangedLinesTest.git(root, "add", "-A");
        GitChangedLinesTest.git(root, "commit", "--quiet", "-m", "the fold");
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":"rr-1","tagSha":"abc"}}
                """.formatted(BASE_RUN));
        ci.answer("GET", BASELINE + "/reports/coverage", """
                [{"id":"r-2","kind":"coverage","kindVersion":1,"stepIndex":2,"highlights":[],
                  "payload":{"sources":[],"total":{"linesCovered":4,"linesTotal":10,"percent":40.0},
                             "baselineTotal":null,"diff":null,"files":[]}}]
                """);
        String put = "/ci/api/runs/" + RUN + "/steps/2/reports/coverage";
        ci.answer("PUT", put, 204, "");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("test-results: not reported (no inputs)\n"
                + "coverage: submitted (13 lines, 30.8% covered)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(result.err()).doesNotContain("WARN");
        JsonNode body = JSON.readTree(ci.requests("PUT", put).getFirst().body());
        assertThat(body.path("kindVersion").asInt()).isEqualTo(1);
        assertThat(body.path("highlights")).isEqualTo(JSON.readTree("""
                [{"severity":"warn","text":"diff coverage 0.0% (0/3 changed lines)","metric":"coverage.diff",
                  "value":0.0,"delta":null},
                 {"severity":"info","text":"coverage 30.8% (-9.2)","metric":"coverage.total","value":30.77,
                  "delta":-9.23}]
                """));
        assertThat(body.path("payload")).isEqualTo(JSON.readTree("""
                {"sources":[{"language":"typescript","tool":"vitest-coverage"}],
                 "total":{"linesCovered":4,"linesTotal":13,"percent":30.77},
                 "baselineTotal":{"version":"2026.1003.52637","percent":40.0},
                 "diff":{"baselineVersion":"2026.1003.52637","linesChanged":3,"linesCovered":0,"percent":0.0,
                         "uncovered":[{"file":"src/app/clock.ts","ranges":[[2,3],[5,5]]}]},
                 "files":[{"file":"src/app/clock.ts","linesCovered":0,"linesTotal":3},
                          {"file":"src/app/ledger.ts","linesCovered":4,"linesTotal":10}]}
                """));
    }

    @Test
    void contractsGoWithTheirHighlightsAgainstTheBaselinesContracts() throws Exception {
        Path root = Fixtures.tree(work);
        Path pacts = Path.of(SubmitCommandTest.class.getResource("/report/contracts/landing/pacts").toURI());
        Files.createDirectories(root.resolve("pacts"));
        try (var files = Files.list(pacts)) {
            for (Path file : files.toList()) {
                Files.copy(file, root.resolve("pacts").resolve(file.getFileName().toString()));
            }
        }
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":"rr-1","tagSha":"abc"}}
                """.formatted(BASE_RUN));
        ci.answer("GET", BASELINE + "/reports/contracts", """
                [{"id":"r-3","kind":"contracts","kindVersion":1,"stepIndex":2,"highlights":[],
                  "payload":{"sources":[],"sides":{"consumer":true,"provider":false,"providerStates":false},
                             "providerStates":null,"pacts":[],"skipped":[],"truncated":false}}]
                """);
        ci.answer("PUT", PUT, 204, "");
        String put = "/ci/api/runs/" + RUN + "/steps/2/reports/contracts";
        ci.answer("PUT", put, 204, "");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("test-results: submitted (18 tests, 10 failed)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: submitted (5 pacts, 16 interactions, 0 states)\n"
                + "entity-changes: not reported (no inputs)\n");
        JsonNode body = JSON.readTree(ci.requests("PUT", put).getFirst().body());
        assertThat(body.path("kindVersion").asInt()).isEqualTo(1);
        assertThat(body.path("payload").path("pacts")).hasSize(5);
        assertThat(body.path("highlights")).hasSize(5).allSatisfy(h -> {
            assertThat(h.path("severity").asText()).isEqualTo("warn");
            assertThat(h.path("text").asText()).startsWith("new pact: qits-landing-app \u2192 qits-");
        });
    }

    @Test
    void entityChangesGoFromStepZeroAgainstTheBaselineTagAndNotFromStepOne() throws Exception {
        Path root = work.resolve("tree");
        Files.createDirectories(root.resolve("docs/database"));
        Files.writeString(root.resolve("docs/database/ci.md"), EntityChangesReportKindTest.diagram("ci",
                EntityChangesReportKindTest.table("ci_run", "uuid id PK \"not null\"")));
        GitChangedLinesTest.git(root, "init", "--quiet");
        GitChangedLinesTest.git(root, "add", "-A");
        GitChangedLinesTest.git(root, "commit", "--quiet", "-m", "the baseline");
        GitChangedLinesTest.git(root, "tag", "2026.1003.52637");
        Files.writeString(root.resolve("docs/database/ci.md"), EntityChangesReportKindTest.diagram("ci",
                EntityChangesReportKindTest.table("ci_report", "uuid id PK \"not null\"")
                        + EntityChangesReportKindTest.table("ci_run", "uuid id PK \"not null\"")));
        GitChangedLinesTest.git(root, "add", "-A");
        GitChangedLinesTest.git(root, "commit", "--quiet", "-m", "the fold");
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":"rr-1","tagSha":"abc"}}
                """.formatted(BASE_RUN));
        env.put("QITS_CI_STEP_INDEX", "0");
        String put = "/ci/api/runs/" + RUN + "/steps/0/reports/entity-changes";
        ci.answer("PUT", put, 204, "");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("test-results: not reported (no inputs)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: submitted (1 unit, 1 changed)\n");
        assertThat(result.err()).doesNotContain("WARN");
        JsonNode body = JSON.readTree(ci.requests("PUT", put).getFirst().body());
        assertThat(body.path("kindVersion").asInt()).isEqualTo(1);
        assertThat(body.path("baseline")).isEqualTo(JSON.readTree("""
                {"runId":"%s","version":"2026.1003.52637"}""".formatted(BASE_RUN)));
        assertThat(body.path("highlights")).isEqualTo(JSON.readTree("""
                [{"severity":"warn","text":"Entities changed since 2026.1003.52637: +1 ~0 −0 tables",
                  "metric":"entities.tables.changed","value":1.0,"delta":null}]
                """));
        JsonNode payload = body.path("payload");
        assertThat(payload.path("baseline")).isEqualTo(JSON.readTree("""
                {"version":"2026.1003.52637","tagSha":"abc","hadDiagram":true}"""));
        assertThat(payload.path("truncated").asBoolean()).isFalse();
        assertThat(payload.path("units")).hasSize(1);
        JsonNode unit = payload.path("units").get(0);
        assertThat(unit.path("file").asText()).isEqualTo("docs/database/ci.md");
        assertThat(unit.path("unit").asText()).isEqualTo("ci");
        assertThat(unit.path("status").asText()).isEqualTo("CHANGED");
        assertThat(unit.path("tables")).isEqualTo(JSON.readTree("""
                [{"name":"ci_report","status":"ADDED","origin":"ci",
                  "columns":{"added":["id: uuid, not null, PK"],"removed":[],"changed":[]}}]"""));
        assertThat(unit.path("before").asText()).startsWith("erDiagram\n").doesNotContain("ci_report");
        assertThat(unit.path("after").asText()).startsWith("erDiagram\n").contains("ci_report {");

        env.put("QITS_CI_STEP_INDEX", "1");
        Result second = submit("--exit-code", "0", "--root", root.toString());

        assertThat(second.exit()).as(second.err()).isZero();
        assertThat(second.out()).endsWith("entity-changes: not reported (no inputs)\n");
        assertThat(ci.requests).extracting(r -> r.method() + " " + r.path()).filteredOn(r -> r.startsWith("PUT"))
                .containsExactly("PUT " + put);
    }

    @Test
    void coverageWithABaselineButNoTagToDiffAgainstGoesWithoutTheDiffAndOneWarning() throws Exception {
        Path root = Fixtures.coverageTree(work);
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":"rr-1","tagSha":"abc"}}
                """.formatted(BASE_RUN));
        String put = "/ci/api/runs/" + RUN + "/steps/2/reports/coverage";
        ci.answer("PUT", put, 204, "");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.err().lines().filter(l -> l.startsWith("WARN"))).singleElement().asString()
                .startsWith("WARN: baseline tag 2026.1003.52637: could not be fetched");
        JsonNode body = JSON.readTree(ci.requests("PUT", put).getFirst().body());
        assertThat(body.path("payload").path("diff").isNull()).isTrue();
        assertThat(body.path("payload").path("baselineTotal").isNull()).isTrue();
        assertThat(body.path("highlights")).isEqualTo(JSON.readTree("""
                [{"severity":"info","text":"coverage 30.8% (no baseline)","metric":"coverage.total","value":30.77,
                  "delta":null}]
                """));
    }

    @Test
    void aBaselineAnswered404IsNoBaselineNotAFailure() throws Exception {
        Path root = Fixtures.tree(work);
        ci.answer("PUT", PUT, 204, "");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.err()).contains("baseline: none (qits-ci answered HTTP 404)");
        assertThat(ci.requests).extracting(r -> r.method() + " " + r.path())
                .containsExactly("GET " + BASELINE, "PUT " + PUT);
        JsonNode body = JSON.readTree(ci.requests("PUT", PUT).getFirst().body());
        assertThat(body.path("baseline").isNull()).isTrue();
        assertThat(body.path("highlights")).hasSize(1);
    }

    @Test
    void aBaselineWithNoReportOfThisVersionIsSentWithoutTheComparison() throws Exception {
        Path root = Fixtures.tree(work);
        ci.answer("GET", BASELINE, """
                {"baseline":{"version":"2026.1003.52637","runId":"%s","releaseRequestId":null,"tagSha":null}}
                """.formatted(BASE_RUN));
        ci.answer("GET", BASELINE_REPORTS, """
                [{"id":"r-1","kind":"test-results","kindVersion":2,"stepIndex":2,"payload":{"another":"schema"}}]""");
        ci.answer("PUT", PUT, 204, "");

        Result result = submit("--exit-code", "1", "--root", root.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        JsonNode body = JSON.readTree(ci.requests("PUT", PUT).getFirst().body());
        assertThat(body.path("baseline").path("version").asText()).isEqualTo("2026.1003.52637");
        assertThat(body.path("highlights")).hasSize(1);
    }

    @Test
    void aBaselineAnswerThatSaysNoneIsNone() throws Exception {
        Path root = Fixtures.tree(work);
        ci.answer("GET", BASELINE, "{\"baseline\":null}");
        ci.answer("PUT", PUT, 204, "");

        Result result = submit("--exit-code", "1", "--root", root.toString());

        assertThat(result.exit()).isZero();
        assertThat(ci.requests).extracting(r -> r.method() + " " + r.path())
                .containsExactly("GET " + BASELINE, "PUT " + PUT);
    }

    @Test
    void anotherRunsTokenRefusedWith403ExitsOne() {
        Path root = Fixtures.tree(work);
        ci.answer("PUT", PUT, 403, "{\"message\":\"this token belongs to another run\"}");

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("test-results: not submitted (HTTP 403)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(result.err()).contains("HTTP 403").contains("this token belongs to another run")
                .doesNotContain(TOKEN);
    }

    @Test
    void aQitsCiWithoutTheDoorsExitsOneNamingThe404() {
        Path root = Fixtures.tree(work);

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("test-results: not submitted (HTTP 404)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
    }

    @Test
    void noInputsMeansNoPut() {
        Result result = submit("--exit-code", "3", "--root", work.toString());

        assertThat(result.exit()).as(result.err()).isZero();
        assertThat(result.out()).isEqualTo("test-results: not reported (no inputs)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(ci.requests("PUT", PUT)).isEmpty();
    }

    @Test
    void anUnreachableQitsCiExitsOne() {
        Path root = Fixtures.tree(work);
        origin = "http://127.0.0.1:1";

        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("test-results: not submitted (qits-ci could not be reached)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(result.err()).contains("WARN: baseline: could not be read, so none");
    }

    @Test
    void theCommandKeepsItsOwnDeadline() {
        Path root = Fixtures.tree(work);
        ci.route("PUT", PUT, request -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new FakePlatform.Answer(204, "", Map.of());
        });
        deadline = Duration.ofMillis(500);

        long started = System.nanoTime();
        Result result = submit("--exit-code", "0", "--root", root.toString());

        assertThat(result.exit()).isEqualTo(1);
        assertThat(result.err()).contains("Gave up after");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
    }

    @Test
    void aMissingVariableIsAUsageErrorAndNothingIsAsked() {
        env.remove("QITS_CI_RUN_ID");
        env.remove("QITS_CI_SHA");

        Result result = submit("--exit-code", "0", "--root", Fixtures.tree(work).toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("QITS_CI_RUN_ID, QITS_CI_SHA are not set");
        assertThat(ci.requests).isEmpty();
    }

    @Test
    void aStepIndexThatIsNotOneIsAUsageError() {
        env.put("QITS_CI_STEP_INDEX", "second");

        assertThat(submit("--exit-code", "0", "--root", work.toString()).exit()).isEqualTo(2);
    }

    @Test
    void theExitCodeIsRequired() {
        Result result = submit("--root", work.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("--exit-code");
    }

    @Test
    void aRootThatIsNotADirectoryIsAUsageError() {
        assertThat(submit("--exit-code", "0", "--root", work.resolve("nope").toString()).exit()).isEqualTo(2);
    }

    @Test
    void anAddressFromAFlagIsRefused() {
        Result result = run("ci", "--ci-url", "http://elsewhere.invalid", "report", "submit", "--exit-code", "0",
                "--root", work.toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("--ci-url does not apply");
        assertThat(ci.requests).isEmpty();
    }

    @Test
    void noCredentialAtAllIsAUsageError() {
        env.remove("QITS_PUBLISH_TOKEN");

        Result result = submit("--exit-code", "0", "--root", Fixtures.tree(work).toString());

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("No CI credential");
        assertThat(ci.requests).isEmpty();
    }

    @Test
    void noCredentialAndNothingToReportIsNotAnError() {
        env.remove("QITS_PUBLISH_TOKEN");

        Result result = submit("--exit-code", "0", "--root", work.toString());

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("test-results: not reported (no inputs)\n"
                + "coverage: not reported (no inputs)\n"
                + "contracts: not reported (no inputs)\n"
                + "entity-changes: not reported (no inputs)\n");
        assertThat(ci.requests).isEmpty();
    }

    @Test
    void theAddressIsComposedFromTheDomainAsThePublishStoreIs() {
        assertThat(eu.wohlben.qits.cli.access.publish.Store.publicOrigin("ci", Map.of("QITS_DOMAIN", " .Example.COM. ")))
                .isEqualTo("https://ci.qits.example.com");
        assertThat(eu.wohlben.qits.cli.access.publish.Store.publicOrigin("ci", Map.of()))
                .isEqualTo("https://ci.qits.wohlben.eu");
    }

    private Result submit(String... args) {
        String[] all = new String[3 + args.length];
        System.arraycopy(new String[] {"ci", "report", "submit"}, 0, all, 0, 3);
        System.arraycopy(args, 0, all, 3, args.length);
        return run(all);
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(), outStream, errStream,
                Clock.systemUTC(), Sleeper.real(), name -> null, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                if (made instanceof SubmitCommand submit) {
                    submit.ciOrigin = origin;
                    submit.deadline = deadline;
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(outStream, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(errStream, true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
