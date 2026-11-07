package eu.wohlben.qits.cli.access.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GoldenMasterCoverageReportKind} (qits-1940): the file {@code QITS_CI_UNCOVERED_OPERATIONS}
 * names, read through the step's environment. The highlight texts are pinned here because qits-projects
 * parses them ({@code ^uncovered operations: (\d+)$}, {@code ^uncovered: (.+)$}).
 */
class GoldenMasterCoverageReportKindTest {

    @TempDir
    Path root;

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> env = new HashMap<>();
    private final GoldenMasterCoverageReportKind kind = new GoldenMasterCoverageReportKind(warnings::add, env::get);

    @Test
    void theRegistryListsTheKindLastWithoutAParser() {
        ReportKinds kinds = ReportKinds.standard(warnings::add, env::get, root.resolve("no-provenance"));
        assertThat(kinds.kinds().getLast()).isInstanceOf(GoldenMasterCoverageReportKind.class);
        assertThat(kinds.kinds().getLast().id()).isEqualTo("golden-master-coverage");
        assertThat(kinds.parsersOf("golden-master-coverage")).isEmpty();
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(GoldenMasterCoverage.class);
    }

    @Test
    void eachLineIsOneOperation() throws Exception {
        Files.createDirectories(root.resolve(".qits-ci"));
        Files.writeString(root.resolve(".qits-ci/uncovered.tsv"),
                "listCommands\tGET\t/commands\n\nDELETE /commands/{id}\tDELETE\t/commands/{id}\n");
        env.put(GoldenMasterCoverageReportKind.FILE, ".qits-ci/uncovered.tsv");

        GoldenMasterCoverage report = kind.collect(Fixtures.step(root), List.of()).orElseThrow();

        assertThat(report.uncovered()).containsExactly(
                new GoldenMasterCoverage.Operation("listCommands", "GET", "/commands"),
                new GoldenMasterCoverage.Operation("DELETE /commands/{id}", "DELETE", "/commands/{id}"));
        assertThat(kind.describe(report)).isEqualTo("2 uncovered: listCommands, DELETE /commands/{id}");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anAbsolutePathIsReadWhereItIs(@TempDir Path elsewhere) throws Exception {
        Path file = Files.writeString(elsewhere.resolve("uncovered.tsv"), "listCommands\tGET\t/commands\n");
        env.put(GoldenMasterCoverageReportKind.FILE, file.toString());

        assertThat(kind.collect(Fixtures.step(root), List.of()).orElseThrow().uncovered())
                .containsExactly(new GoldenMasterCoverage.Operation("listCommands", "GET", "/commands"));
    }

    @Test
    void thePayloadIsUncoveredWithNameMethodAndPath() throws Exception {
        GoldenMasterCoverage report = new GoldenMasterCoverage(
                List.of(new GoldenMasterCoverage.Operation("listCommands", "GET", "/commands")));

        String json = ReportJson.MAPPER.writeValueAsString(report);

        assertThat(json).isEqualTo("{\"uncovered\":[{\"name\":\"listCommands\",\"method\":\"GET\",\"path\":\"/commands\"}]}");
        assertThat(ReportJson.MAPPER.readValue(json, GoldenMasterCoverage.class)).isEqualTo(report);
    }

    // --- a clean pass is reported -----------------------------------------------------------------

    @Test
    void anEmptyFileIsAReportWithNothingUncovered() throws Exception {
        Files.writeString(root.resolve("uncovered.tsv"), "");
        env.put(GoldenMasterCoverageReportKind.FILE, "uncovered.tsv");

        GoldenMasterCoverage report = kind.collect(Fixtures.step(root), List.of()).orElseThrow();

        assertThat(report.uncovered()).isEmpty();
        assertThat(kind.describe(report)).isEqualTo("0 uncovered");
        assertThat(kind.highlight(report, Optional.empty(), Fixtures.step(root))).containsExactly(
                new Highlight(Highlight.GOOD, "uncovered operations: 0", "operations.uncovered", 0.0, null));
        Files.writeString(root.resolve("uncovered.tsv"), "\n  \n");
        assertThat(kind.collect(Fixtures.step(root), List.of()).orElseThrow().uncovered()).isEmpty();
    }

    // --- not reported ----------------------------------------------------------------------------

    @Test
    void withoutTheVariableNothingIsReported() throws Exception {
        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        env.put(GoldenMasterCoverageReportKind.FILE, "  ");
        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        assertThat(kind.notReported()).isEqualTo("no coverage check in this step");
    }

    @Test
    void aMissingFileIsNotReported() throws Exception {
        env.put(GoldenMasterCoverageReportKind.FILE, ".qits-ci/uncovered.tsv");

        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aLineThatIsNotThreeFieldsIsSkippedWithAWarning() throws Exception {
        Files.writeString(root.resolve("uncovered.tsv"),
                "listCommands GET /commands\nonly\ttwo\nlistCommands\tGET\t/commands\n");
        env.put(GoldenMasterCoverageReportKind.FILE, "uncovered.tsv");

        GoldenMasterCoverage report = kind.collect(Fixtures.step(root), List.of()).orElseThrow();

        assertThat(report.uncovered()).containsExactly(
                new GoldenMasterCoverage.Operation("listCommands", "GET", "/commands"));
        assertThat(warnings).containsExactly(
                "golden-master-coverage: skipped line 1 of uncovered.tsv: not <name>\\t<method>\\t<path>",
                "golden-master-coverage: skipped line 2 of uncovered.tsv: not <name>\\t<method>\\t<path>");
    }

    // --- highlights ------------------------------------------------------------------------------

    @Test
    void theCountComesFirstThenOneBadHighlightPerOperation() {
        GoldenMasterCoverage report = new GoldenMasterCoverage(List.of(
                new GoldenMasterCoverage.Operation("listCommands", "GET", "/commands"),
                new GoldenMasterCoverage.Operation("DELETE /commands/{id}", "DELETE", "/commands/{id}")));

        List<Highlight> highlights = kind.highlight(report, Optional.empty(), Fixtures.step(root));

        assertThat(highlights).containsExactly(
                new Highlight(Highlight.BAD, "uncovered operations: 2", "operations.uncovered", 2.0, null),
                new Highlight(Highlight.BAD, "uncovered: listCommands", null, null, null),
                new Highlight(Highlight.BAD, "uncovered: DELETE /commands/{id}", null, null, null));
        assertThat(highlights.getFirst().text()).matches("^uncovered operations: (\\d+)$");
        assertThat(highlights.subList(1, 3)).allSatisfy(h -> assertThat(h.text()).matches("^uncovered: (.+)$"));
    }

    @Test
    void atMostNineOperationsAreNamedSoAReportKeepsTenHighlights() {
        GoldenMasterCoverage report = new GoldenMasterCoverage(IntStream.range(0, 12)
                .mapToObj(i -> new GoldenMasterCoverage.Operation("op" + i, "GET", "/op/" + i)).toList());

        List<Highlight> highlights = kind.highlight(report, Optional.empty(), Fixtures.step(root));

        assertThat(highlights).hasSize(Highlight.MAX_PER_REPORT);
        assertThat(highlights.getFirst().text()).isEqualTo("uncovered operations: 12");
        assertThat(highlights.get(1).text()).isEqualTo("uncovered: op0");
        assertThat(highlights.getLast().text()).isEqualTo("uncovered: op8");
        assertThat(report.uncovered()).hasSize(12);
    }
}
