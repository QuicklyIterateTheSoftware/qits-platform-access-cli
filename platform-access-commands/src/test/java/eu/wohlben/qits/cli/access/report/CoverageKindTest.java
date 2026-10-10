package eu.wohlben.qits.cli.access.report;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageKindTest {

    static final Baseline BASELINE = new Baseline("2026.1003.52637", "run-0", "rr-0", null);

    @TempDir
    Path work;

    private final List<String> warnings = new ArrayList<>();
    private final CoverageKind kind = new CoverageKind(warnings::add);

    @Test
    void theRegistryListsTheKindAndItsTwoParsers() {
        ReportKinds kinds = ReportKinds.standard(warnings::add);

        assertThat(kinds.parsersOf("coverage")).extracting(ReportParser::tool)
                .containsExactly("jacoco", "vitest-coverage");
        assertThat(kind.id()).isEqualTo("coverage");
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(Coverage.class);
    }

    @Test
    void javaAndTypescriptInOneStepMergeIntoOneTotal() throws Exception {
        Path root = Fixtures.coverageTree(work);
        JacocoFixture.build(root);

        Coverage report = kind.collect(Fixtures.step(root), ReportKinds.standard(warnings::add).parsersOf("coverage"))
                .orElseThrow();

        assertThat(report.sources()).containsExactly(new Coverage.Source("java", "jacoco"),
                new Coverage.Source("typescript", "vitest-coverage"));
        // Ledger.java 5/7, Clock.java 0/2, ledger.ts 4/10, clock.ts 0/3.
        assertThat(report.total()).isEqualTo(new Coverage.Total(9, 22, 40.91));
        assertThat(report.files()).containsExactly(
                new Coverage.FileCoverage(JacocoFixture.CLOCK, 0, 2),
                new Coverage.FileCoverage(JacocoFixture.LEDGER, 5, 7),
                new Coverage.FileCoverage("src/app/clock.ts", 0, 3),
                new Coverage.FileCoverage("src/app/ledger.ts", 4, 10));
        assertThat(report.baselineTotal()).isNull();
        assertThat(report.diff()).as("no baseline, no diff").isNull();
        assertThat(warnings).isEmpty();
        assertThat(kind.describe(report)).isEqualTo("22 lines, 40.9% covered");
    }

    @Test
    void theSameFileFromTwoSourcesIsCoveredWhereEitherCoveredIt() throws IOException {
        Coverage report = collect(step(Optional.empty(), ChangedLines.unavailable()),
                coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true, 2, false, 3, false))),
                coverage("java", "jacoco", Map.of("a/A.java", Map.of(2, true, 4, false))));

        assertThat(report.sources()).containsExactly(new Coverage.Source("java", "jacoco"));
        assertThat(report.total()).isEqualTo(new Coverage.Total(2, 4, 50.0));
    }

    @Test
    void noInputsIsNotReported() throws IOException {
        assertThat(kind.collect(Fixtures.step(work), ReportKinds.standard(warnings::add).parsersOf("coverage")))
                .isEmpty();
    }

    @Test
    void anUnreadableFileIsSkippedWithAWarning() throws Exception {
        Path root = Fixtures.coverageTree(work);
        Path broken = Files.createDirectories(root.resolve(".qits-reports"));
        Files.writeString(broken.resolve("jacoco.exec"), "garbage");

        Coverage report = kind.collect(Fixtures.step(root), ReportKinds.standard(warnings::add).parsersOf("coverage"))
                .orElseThrow();

        assertThat(report.sources()).containsExactly(new Coverage.Source("typescript", "vitest-coverage"));
        assertThat(warnings).singleElement().asString().startsWith("coverage: skipped .qits-reports/jacoco.exec (jacoco): ");
    }

    @Test
    void theDiffCountsOnlyChangedLinesThatAreCoverable() throws IOException {
        ChangedLines changed = changed(Map.of(
                "a/A.java", Set.of(1, 2, 3, 4, 5, 9, 10, 11),
                "b/B.ts", Set.of(1),
                "README.md", Set.of(1, 2)));
        Coverage report = collect(step(Optional.of(BASELINE), changed),
                coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true, 2, false, 3, false, 5, false,
                        9, true, 10, false, 11, false, 20, false))),
                coverage("typescript", "vitest-coverage", Map.of("b/B.ts", Map.of(1, true))));

        assertThat(report.diff()).isEqualTo(new Coverage.Diff("2026.1003.52637", 8, 3, 37.5, List.of(
                new Coverage.Uncovered("a/A.java", List.of(List.of(2, 3), List.of(5, 5), List.of(10, 11)))))
        );
    }

    @Test
    void aDiffWithNoCoverableChangeHasNoPercent() throws IOException {
        Coverage report = collect(step(Optional.of(BASELINE), changed(Map.of("README.md", Set.of(1)))),
                coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true))));

        assertThat(report.diff()).isEqualTo(new Coverage.Diff("2026.1003.52637", 0, 0, null, List.of()));
        assertThat(kind.highlight(report, Optional.empty(), step(Optional.of(BASELINE), ChangedLines.unavailable())))
                .first().isEqualTo(new Highlight("info", "diff coverage: no coverable line changed", "coverage.diff",
                        null, null));
    }

    @Test
    void changedLinesThatCouldNotBeHadLeaveTheDiffOut() throws IOException {
        Coverage report = collect(step(Optional.of(BASELINE), ChangedLines.unavailable()),
                coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true))));

        assertThat(report.diff()).isNull();
    }

    @Test
    void theBaselinesTotalIsKeptAndItsDeltaHighlighted() throws IOException {
        StepContext step = step(Optional.of(BASELINE), changed(Map.of("a/A.java", Set.of(1, 2, 3, 4, 5))));
        Coverage report = collect(step, coverage("java", "jacoco",
                Map.of("a/A.java", Map.of(1, true, 2, true, 3, true, 4, true, 5, false, 6, false, 7, true))));
        Coverage baseline = new Coverage(List.of(), new Coverage.Total(81, 100, 81.0), null, null, List.of());

        Coverage compared = kind.compared(report, Optional.of(baseline), step);

        assertThat(compared.baselineTotal()).isEqualTo(new Coverage.BaselineTotal("2026.1003.52637", 81.0));
        assertThat(compared.total().percent()).isEqualTo(71.43);
        assertThat(kind.highlight(compared, Optional.of(baseline), step)).containsExactly(
                new Highlight("good", "diff coverage 80.0% (4/5 changed lines)", "coverage.diff", 80.0, null),
                new Highlight("info", "coverage 71.4% (-9.6)", "coverage.total", 71.43, -9.57));
    }

    @Test
    void diffCoverageIsWarnBelowFiftyInfoBetweenAndNeverBad() throws IOException {
        StepContext step = step(Optional.of(BASELINE), changed(Map.of("a/A.java", Set.of(1, 2, 3, 4))));

        assertThat(severity(step, Map.of(1, true, 2, false, 3, false, 4, false))).isEqualTo("warn");
        assertThat(severity(step, Map.of(1, true, 2, true, 3, false, 4, false))).isEqualTo("info");
        assertThat(severity(step, Map.of(1, true, 2, true, 3, true, 4, false))).isEqualTo("info");
        assertThat(severity(step, Map.of(1, false, 2, false, 3, false, 4, false))).isEqualTo("warn");
        assertThat(severity(step, Map.of(1, true, 2, true, 3, true, 4, true))).isEqualTo("good");
    }

    @Test
    void aBaselineWithoutACoverageReportIsNoBaseline() throws IOException {
        StepContext step = step(Optional.of(BASELINE), ChangedLines.unavailable());
        Coverage report = collect(step, coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true, 2, false))));

        Coverage compared = kind.compared(report, Optional.empty(), step);

        assertThat(compared.baselineTotal()).isNull();
        assertThat(kind.highlight(compared, Optional.empty(), step)).containsExactly(
                new Highlight("info", "coverage 50.0% (no baseline)", "coverage.total", 50.0, null));
    }

    @Test
    void aRedStepsCoverageIsPartialAndNeverComparedWithTheBaseline() throws IOException {
        StepContext step = new StepContext(work, "run-1", 2, Fixtures.REPOSITORY, Fixtures.COMMIT, 1,
                Optional.of(BASELINE), changed(Map.of("a/A.java", Set.of(1, 2))), TestCaseLocators.registered());
        Coverage report = collect(step, coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, false, 2, false))));
        Coverage baseline = new Coverage(List.of(), new Coverage.Total(81, 100, 81.0), null, null, List.of());

        Coverage compared = kind.compared(report, Optional.of(baseline), step);

        assertThat(compared.baselineTotal()).isNull();
        assertThat(kind.highlight(compared, Optional.of(baseline), step)).containsExactly(
                new Highlight("info", "diff coverage 0.0% (0/2 changed lines, partial run)", "coverage.diff", 0.0,
                        null),
                new Highlight("info", "coverage 0.0% (partial: step exited 1, not compared)", "coverage.total", 0.0,
                        null));
    }

    @Test
    void anUnchangedTotalSaysSo() throws IOException {
        StepContext step = step(Optional.of(BASELINE), ChangedLines.unavailable());
        Coverage report = collect(step, coverage("java", "jacoco", Map.of("a/A.java", Map.of(1, true, 2, false))));
        Coverage baseline = new Coverage(List.of(), new Coverage.Total(1, 2, 50.0), null, null, List.of());

        assertThat(kind.highlight(kind.compared(report, Optional.of(baseline), step), Optional.of(baseline), step))
                .extracting(Highlight::text).containsExactly("coverage 50.0% (±0.0)");
    }

    @Test
    void thePayloadIsTheEpicsVersionOneAndReadsBack() throws Exception {
        StepContext step = step(Optional.of(BASELINE), changed(Map.of("a/A.java", Set.of(2))));
        Coverage report = kind.compared(collect(step, coverage("java", "jacoco",
                Map.of("a/A.java", Map.of(1, true, 2, false)))), Optional.empty(), step);

        JsonNode json = ReportJson.MAPPER.valueToTree(report);

        assertThat(json).isEqualTo(ReportJson.MAPPER.readTree("""
                {"sources":[{"language":"java","tool":"jacoco"}],
                 "total":{"linesCovered":1,"linesTotal":2,"percent":50.0},
                 "baselineTotal":null,
                 "diff":{"baselineVersion":"2026.1003.52637","linesChanged":1,"linesCovered":0,"percent":0.0,
                         "uncovered":[{"file":"a/A.java","ranges":[[2,2]]}]},
                 "files":[{"file":"a/A.java","linesCovered":1,"linesTotal":2}]}
                """));
        assertThat(ReportJson.MAPPER.treeToValue(json, Coverage.class)).isEqualTo(report);
    }

    @Test
    void consecutiveLinesAreOneRange() {
        assertThat(CoverageKind.ranges(List.of(1, 2, 3, 7, 9, 10))).containsExactly(List.of(1, 3), List.of(7, 7),
                List.of(9, 10));
        assertThat(CoverageKind.ranges(List.of(4))).containsExactly(List.of(4, 4));
    }

    // ---------------------------------------------------------------------------------------------

    private String severity(StepContext step, Map<Integer, Boolean> lines) throws IOException {
        Coverage report = collect(step, coverage("java", "jacoco", Map.of("a/A.java", lines)));
        List<Highlight> highlights = kind.highlight(report, Optional.empty(), step);
        assertThat(highlights).extracting(Highlight::severity).doesNotContain("bad");
        return highlights.getFirst().severity();
    }

    private Coverage collect(StepContext step, LineCoverage... sources) throws IOException {
        List<ReportParser<?>> parsers = new ArrayList<>();
        for (LineCoverage source : sources) {
            parsers.add(new Fixed(source));
        }
        return kind.collect(step, parsers).orElseThrow();
    }

    private StepContext step(Optional<Baseline> baseline, ChangedLines changed) {
        return new StepContext(work, "run-1", 2, Fixtures.REPOSITORY, Fixtures.COMMIT, 0, baseline, changed,
                TestCaseLocators.registered());
    }

    private static LineCoverage coverage(String language, String tool, Map<String, Map<Integer, Boolean>> files) {
        LineCoverage coverage = LineCoverage.of(language, tool);
        files.forEach((file, lines) -> lines.forEach((line, covered) -> coverage.line(file, line, covered)));
        return coverage;
    }

    private static ChangedLines changed(Map<String, Set<Integer>> lines) {
        return new ChangedLines() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public Set<String> files() {
                return lines.keySet();
            }

            @Override
            public Set<Integer> lines(String file) {
                return lines.getOrDefault(file, Set.of());
            }
        };
    }

    /** A parser that "finds" one file and answers the coverage it was given. */
    private record Fixed(LineCoverage coverage) implements ReportParser<LineCoverage> {

        @Override
        public String kind() {
            return CoverageKind.ID;
        }

        @Override
        public String language() {
            return coverage.language();
        }

        @Override
        public String tool() {
            return coverage.tool();
        }

        @Override
        public List<Path> discover(Path root) {
            return List.of(root.resolve("fixed"));
        }

        @Override
        public LineCoverage parse(Path file, StepContext step) {
            return coverage;
        }
    }
}
