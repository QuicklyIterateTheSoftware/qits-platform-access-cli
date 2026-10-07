package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The test-results kind over the whole fixture tree: its totals, its suites, its caps and its highlights. */
class TestResultsKindTest {

    private static final Baseline BASELINE = new Baseline("2026.1003.52637", "base-run", "rr-1", "abc123");

    @TempDir
    Path work;

    private final List<String> warnings = new ArrayList<>();
    private final ReportKinds kinds = ReportKinds.standard(warnings::add);
    private final TestResultsKind kind = (TestResultsKind) kinds.kinds().getFirst();

    @Test
    void theRegistryListsTheKindAndItsThreeParsers() {
        assertThat(kinds.kinds()).extracting(ReportKind::id).containsExactly("test-results", "coverage", "contracts");
        assertThat(kinds.parsersOf("test-results")).extracting(ReportParser::tool)
                .containsExactly("surefire", "failsafe", "vitest");
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(TestResults.class);
    }

    @Test
    void theTotalsAddUpEveryToolAndTheSuitesAreOnePerToolAndModule() throws Exception {
        TestResults report = collect(Fixtures.tree(work)).orElseThrow();

        // The fixture's own logs: surefire "Tests run: 10, Failures: 2, Errors: 4, Skipped: 1", failsafe
        // "Tests run: 2, Failures: 1", vitest 6 tests, 3 failed, 1 skipped.
        assertThat(report.totals().tests()).isEqualTo(18);
        assertThat(report.totals().failed()).isEqualTo(6);
        assertThat(report.totals().errored()).isEqualTo(4);
        assertThat(report.totals().skipped()).isEqualTo(2);
        assertThat(report.totals().passed()).isEqualTo(6);
        assertThat(report.suites()).containsExactly(
                new TestResults.Suite("java", "surefire", "service", 10, 2, 4, 1, 388),
                new TestResults.Suite("java", "failsafe", "service", 2, 1, 0, 0, 70),
                new TestResults.Suite("typescript", "vitest", ".", 6, 3, 0, 1, 118));
        assertThat(report.totals().durationMs()).isEqualTo(388 + 70 + 118);
        assertThat(report.failures()).hasSize(6 + 1 + 3);
        assertThat(report.failures()).allSatisfy(f -> {
            assertThat(f.coordinates().commitSha()).isEqualTo(Fixtures.COMMIT);
            assertThat(f.coordinates().repository()).isEqualTo(Fixtures.REPOSITORY);
            assertThat(f.coordinates().className()).isNotBlank();
            assertThat(f.coordinates().testName()).isNotBlank();
        });
        assertThat(report.truncated()).isFalse();
        assertThat(warnings).isEmpty();
    }

    @Test
    void thePayloadIsTheEpicsShape() throws Exception {
        TestResults report = collect(Fixtures.tree(work)).orElseThrow();
        JsonNode json = ReportJson.MAPPER.valueToTree(report);

        assertThat(json.fieldNames()).toIterable().containsExactly("totals", "suites", "failures", "truncated");
        assertThat(json.path("totals").fieldNames()).toIterable()
                .containsExactly("tests", "passed", "failed", "errored", "skipped", "durationMs");
        assertThat(json.path("suites").get(0).fieldNames()).toIterable()
                .containsExactly("language", "tool", "module", "tests", "failed", "errored", "skipped", "durationMs");
        JsonNode failure = json.path("failures").get(0);
        assertThat(failure.fieldNames()).toIterable()
                .containsExactly("coordinates", "shape", "failureType", "message", "stackTrace", "durationMs");
        assertThat(failure.path("coordinates").fieldNames()).toIterable().containsExactly("language", "tool",
                "repository", "commitSha", "file", "className", "testName", "lineStart", "lineEnd");
        assertThat(failure.path("coordinates").path("lineStart").isNull()).isTrue();
        assertThat(failure.path("coordinates").path("repository").path("name").asText()).isEqualTo("qits-fx-service");
        assertThat(ReportJson.MAPPER.treeToValue(json, TestResults.class)).isEqualTo(report);
    }

    @Test
    void noInputsIsNotReported() throws Exception {
        assertThat(collect(work)).isEmpty();
    }

    @Test
    void aFileThatDoesNotParseIsSkippedWithAWarning() throws Exception {
        Path root = Fixtures.tree(work);
        Path reports = root.resolve("service/target/surefire-reports");
        Files.writeString(reports.resolve("TEST-a.Broken.xml"), "<testsuite name=\"a.Broken\"><testcase");
        Files.writeString(reports.resolve("TEST-a.Html.xml"), "<html><body>not a report</body></html>");

        TestResults report = collect(root).orElseThrow();

        assertThat(report.totals().tests()).isEqualTo(18);
        assertThat(warnings).hasSize(2);
        assertThat(warnings.get(0)).startsWith("test-results: skipped service/target/surefire-reports/TEST-a.Broken.xml"
                + " (surefire): not JUnit XML");
        assertThat(warnings.get(1)).contains("TEST-a.Html.xml").contains("the root element is <html>");
    }

    @Test
    void onlyFilesThatDoNotParseIsNotReported() throws Exception {
        Path reports = Files.createDirectories(work.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-a.Broken.xml"), "garbage");

        assertThat(collect(work)).isEmpty();
        assertThat(warnings).hasSize(1);
    }

    @Test
    void atMostTwoHundredFailuresAreKeptAndTheMessagesAreCapped() throws Exception {
        Path reports = Files.createDirectories(work.resolve("target/surefire-reports"));
        StringBuilder xml = new StringBuilder("<testsuite name=\"a.ManyTest\" time=\"1.5\">");
        String longMessage = "é".repeat(5000);
        String longTrace = "x".repeat(40_000);
        for (int i = 0; i < 250; i++) {
            xml.append("<testcase name=\"case").append(i).append("\" classname=\"a.ManyTest\" time=\"0.001\">")
                    .append("<failure message=\"").append(longMessage).append("\" type=\"java.lang.AssertionError\">")
                    .append(longTrace).append("</failure></testcase>");
        }
        xml.append("<testcase name=\"passes\" classname=\"a.ManyTest\"/></testsuite>");
        Files.writeString(reports.resolve("TEST-a.ManyTest.xml"), xml);

        TestResults report = collect(work).orElseThrow();

        assertThat(report.totals().tests()).isEqualTo(251);
        assertThat(report.totals().failed()).isEqualTo(250);
        assertThat(report.failures()).hasSize(TestResultsKind.MAX_FAILURES);
        assertThat(report.truncated()).isTrue();
        assertThat(report.failures()).allSatisfy(f -> {
            assertThat(f.message().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
            assertThat(f.message()).endsWith("…");
            assertThat(f.coordinates().file()).isNull();
        });
        // 200 traces at 16 KiB would not fit what qits-ci takes; they are cut so the report still does.
        assertThat(ReportJson.MAPPER.writeValueAsBytes(report).length).isLessThanOrEqualTo(TestResultsKind.PAYLOAD_BUDGET);
        // The messages at their full 4 KiB leave no room for any trace; the messages stay whole.
        assertThat(report.failures().getFirst().stackTrace()).isNull();
        assertThat(report.failures().getFirst().message().getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(4000);
    }

    @Test
    void aReportThatFitsKeepsItsWholeStackTraces() {
        String trace = "t".repeat(20_000);
        TestResults.Failure one = new TestResults.Failure(new TestCoordinates("java", "surefire", Fixtures.REPOSITORY,
                Fixtures.COMMIT, null, "a.B", "c", null, null), "ERROR", null, "m", Text.cap(trace, 16384), 1L);
        TestResults small = new TestResults(new TestResults.Totals(1, 0, 0, 1, 0, 1), List.of(), List.of(one), false);

        assertThat(TestResultsKind.fitted(small)).isEqualTo(small);
        assertThat(one.stackTrace().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(16384);
    }

    @Test
    void longTracesAreCutBeforeTheyAreDropped() {
        List<TestResults.Failure> many = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            many.add(new TestResults.Failure(new TestCoordinates("java", "surefire", Fixtures.REPOSITORY,
                    Fixtures.COMMIT, null, "a.B", "case" + i, null, null), "ERROR", "java.lang.IllegalStateException",
                    "short", Text.cap("t".repeat(20_000), TestResultsKind.STACK_TRACE_BYTES), 1L));
        }
        TestResults big = new TestResults(new TestResults.Totals(200, 0, 0, 200, 0, 1), List.of(), many, false);

        TestResults fitted = TestResultsKind.fitted(big);

        assertThat(fitted.failures()).allSatisfy(f -> {
            assertThat(f.stackTrace().getBytes(StandardCharsets.UTF_8).length)
                    .isLessThanOrEqualTo(TestResultsKind.SHORT_STACK_TRACE_BYTES);
            assertThat(f.message()).isEqualTo("short");
        });
    }

    @Test
    void aRegisteredLocatorFillsInTheLines() throws Exception {
        Path root = Fixtures.tree(work);
        TestCaseLocator everywhere = new TestCaseLocator() {
            @Override
            public boolean supports(String language, String tool) {
                return language.equals("java");
            }

            @Override
            public Optional<LineRange> locate(Path at, TestCoordinates test) {
                return Optional.of(new LineRange(10, 20));
            }
        };
        StepContext step = new StepContext(root, "run-1", 0, Fixtures.REPOSITORY, Fixtures.COMMIT, 1, Optional.empty(),
                ChangedLines.unavailable(), new TestCaseLocators(List.of(everywhere)));

        TestResults report = kind.collect(step, kinds.parsersOf(kind.id())).orElseThrow();

        assertThat(report.failures()).filteredOn(f -> f.coordinates().language().equals("java"))
                .allSatisfy(f -> assertThat(f.coordinates().lineStart()).isEqualTo(10));
        assertThat(report.failures()).filteredOn(f -> f.coordinates().language().equals("typescript"))
                .allSatisfy(f -> assertThat(f.coordinates().lineStart()).isNull());
    }

    @Test
    void theRegisteredLocatorsFillInTheLinesFromTheSourceTree() throws Exception {
        Path root = Fixtures.tree(work);
        // A failing class whose source is nowhere in the tree: its file is null, and so are its lines.
        Files.writeString(root.resolve("service/target/surefire-reports/TEST-eu.wohlben.qits.fx.GoneTest.xml"),
                "<testsuite name=\"eu.wohlben.qits.fx.GoneTest\"><testcase name=\"vanished\" "
                        + "classname=\"eu.wohlben.qits.fx.GoneTest\"><failure message=\"m\" "
                        + "type=\"java.lang.AssertionError\">trace</failure></testcase></testsuite>");

        TestResults report = collect(root).orElseThrow();

        assertThat(report.failures()).extracting(f -> f.coordinates().className() + "#" + f.coordinates().testName()
                        + " " + f.coordinates().lineStart() + "-" + f.coordinates().lineEnd())
                .containsExactlyInAnyOrder(
                        "eu.wohlben.qits.fx.BrokenSetupTest#(setup) null-null",
                        "eu.wohlben.qits.fx.ClockTest#waitsTooLong 15-19",
                        "eu.wohlben.qits.fx.GoneTest#vanished null-null",
                        "eu.wohlben.qits.fx.LedgerTest#readsTheLedger 21-25",
                        "eu.wohlben.qits.fx.LedgerTest#refusesAnotherRunsToken 16-19",
                        "eu.wohlben.qits.fx.LedgerTest$WhenEmpty#refusesAWithdrawal 40-43",
                        "eu.wohlben.qits.fx4.LegacyTest#(setup) null-null",
                        "eu.wohlben.qits.fx.LedgerIT#servesTheLedger 13-16",
                        "src/app/broken.spec.ts#(setup) null-null",
                        "Ledger > when empty#refuses a withdrawal 9-11",
                        "Ledger#waits too long 14-16");
        assertThat(report.failures()).filteredOn(f -> f.coordinates().className().endsWith("GoneTest"))
                .singleElement().satisfies(f -> assertThat(f.coordinates().file()).isNull());
    }

    // --- highlights --------------------------------------------------------------------------------

    @Test
    void failuresAreOneBadLineWithTheErrorsCountedIn() throws Exception {
        StepContext step = Fixtures.step(Fixtures.tree(work), 1, Optional.empty());
        TestResults report = collect(step.root()).orElseThrow();

        assertThat(kind.highlight(report, Optional.empty(), step))
                .containsExactly(new Highlight("bad", "10 tests failed", "tests.failed", 10.0, null));
        assertThat(kind.describe(report)).isEqualTo("18 tests, 10 failed");
    }

    @Test
    void aGreenRunSaysAllPassedAndComparesTheCountWithTheBaseline() {
        StepContext step = Fixtures.step(work, 0, Optional.of(BASELINE));

        assertThat(kind.highlight(totals(412, 0, 0, 3), Optional.of(totals(409, 1, 0, 0)), step)).containsExactly(
                new Highlight("good", "all 409 tests passed, 3 skipped", "tests.passed", 409.0, null),
                new Highlight("info", "+3 tests vs 2026.1003.52637", "tests.total", 412.0, 3.0));
        assertThat(kind.highlight(totals(400, 0, 0, 0), Optional.of(totals(409, 1, 0, 0)), step))
                .contains(new Highlight("info", "-9 tests vs 2026.1003.52637", "tests.total", 400.0, -9.0));
        assertThat(kind.highlight(totals(409, 1, 0, 0), Optional.of(totals(409, 2, 0, 0)), step))
                .containsExactly(new Highlight("bad", "1 test failed", "tests.failed", 1.0, null));
    }

    @Test
    void withoutABaselineThereIsNoComparison() {
        assertThat(kind.highlight(totals(10, 0, 0, 0), Optional.empty(), Fixtures.step(work)))
                .containsExactly(new Highlight("good", "all 10 tests passed", "tests.passed", 10.0, null));
    }

    @Test
    void aRedStepWithNoFailingTestSaysSo() {
        assertThat(kind.highlight(totals(10, 0, 0, 0), Optional.empty(), Fixtures.step(work, 2, Optional.empty())))
                .containsExactly(new Highlight("good", "all 10 tests passed", "tests.passed", 10.0, null),
                        new Highlight("warn", "step exited 2, no test failed", null, null, null));
    }

    @Test
    void aHighlightIsCutAtEightyCharacters() {
        Highlight long_ = new Highlight("info", "x".repeat(200), null, null, null);

        assertThat(long_.text()).hasSize(80).endsWith("…");
    }

    private Optional<TestResults> collect(Path root) throws Exception {
        return kind.collect(Fixtures.step(root), kinds.parsersOf(kind.id()));
    }

    private static TestResults totals(int tests, int failed, int errored, int skipped) {
        return new TestResults(new TestResults.Totals(tests, tests - failed - errored - skipped, failed, errored, skipped,
                1000), List.of(), List.of(), false);
    }
}
