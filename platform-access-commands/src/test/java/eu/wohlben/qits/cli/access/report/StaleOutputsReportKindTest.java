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
 * {@link StaleOutputsReportKind} (qits-1920): the file {@code QITS_CI_STALE_OUTPUTS} names, read
 * through the step's environment.
 */
class StaleOutputsReportKindTest {

    static final String TAPES = "qits release-request regenerate --project p --repository r --kind cli-tapes";
    static final String DIAGRAM = "qits release-request regenerate --project p --repository r --kind entity-diagram";

    @TempDir
    Path root;

    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> env = new HashMap<>();
    private final StaleOutputsReportKind kind = new StaleOutputsReportKind(warnings::add, env::get);

    @Test
    void theRegistryListsTheKindLast() {
        ReportKinds kinds = ReportKinds.standard(warnings::add, env::get, root.resolve("no-provenance"));
        assertThat(kinds.kinds().getLast()).isInstanceOf(StaleOutputsReportKind.class);
        assertThat(kinds.kinds().getLast().id()).isEqualTo("stale-outputs");
        assertThat(kinds.parsersOf("stale-outputs")).isEmpty();
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(StaleOutputs.class);
    }

    @Test
    void eachLineIsOneKindWithItsCommand() throws Exception {
        Files.createDirectories(root.resolve(".qits-ci"));
        Files.writeString(root.resolve(".qits-ci/stale-outputs.tsv"),
                "cli-tapes\t" + TAPES + "\n\nentity-diagram\t" + DIAGRAM + "\n");
        env.put(StaleOutputsReportKind.FILE, ".qits-ci/stale-outputs.tsv");

        StaleOutputs report = kind.collect(Fixtures.step(root), List.of()).orElseThrow();

        assertThat(report.kinds()).containsExactly(new StaleOutputs.Kind("cli-tapes", TAPES),
                new StaleOutputs.Kind("entity-diagram", DIAGRAM));
        assertThat(kind.describe(report)).isEqualTo("2 stale: cli-tapes, entity-diagram");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anAbsolutePathIsReadWhereItIs(@TempDir Path elsewhere) throws Exception {
        Path file = Files.writeString(elsewhere.resolve("stale.tsv"), "cli-tapes\t" + TAPES + "\n");
        env.put(StaleOutputsReportKind.FILE, file.toString());

        assertThat(kind.collect(Fixtures.step(root), List.of()).orElseThrow().kinds())
                .containsExactly(new StaleOutputs.Kind("cli-tapes", TAPES));
    }

    @Test
    void thePayloadIsKindsWithKindAndRegenerate() throws Exception {
        StaleOutputs report = new StaleOutputs(List.of(new StaleOutputs.Kind("cli-tapes", TAPES)));

        String json = ReportJson.MAPPER.writeValueAsString(report);

        assertThat(json).isEqualTo("{\"kinds\":[{\"kind\":\"cli-tapes\",\"regenerate\":\"" + TAPES + "\"}]}");
        assertThat(ReportJson.MAPPER.readValue(json, StaleOutputs.class)).isEqualTo(report);
    }

    // --- not reported ----------------------------------------------------------------------------

    @Test
    void withoutTheVariableNothingIsReported() throws Exception {
        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        env.put(StaleOutputsReportKind.FILE, "  ");
        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        assertThat(kind.notReported()).isEqualTo("no stale outputs");
    }

    @Test
    void aMissingFileIsNotReported() throws Exception {
        env.put(StaleOutputsReportKind.FILE, ".qits-ci/stale-outputs.tsv");

        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        assertThat(warnings).isEmpty();
    }

    @Test
    void aFileOfBlankLinesIsNotReported() throws Exception {
        Files.writeString(root.resolve("stale.tsv"), "\n  \n \n");
        env.put(StaleOutputsReportKind.FILE, "stale.tsv");

        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        Files.writeString(root.resolve("stale.tsv"), "");
        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
    }

    @Test
    void aLineWithoutATabIsSkippedWithAWarning() throws Exception {
        Files.writeString(root.resolve("stale.tsv"), "cli-tapes " + TAPES + "\nentity-diagram\t" + DIAGRAM + "\n");
        env.put(StaleOutputsReportKind.FILE, "stale.tsv");

        StaleOutputs report = kind.collect(Fixtures.step(root), List.of()).orElseThrow();

        assertThat(report.kinds()).containsExactly(new StaleOutputs.Kind("entity-diagram", DIAGRAM));
        assertThat(warnings).containsExactly("stale-outputs: skipped line 1 of stale.tsv: no tab");
    }

    @Test
    void onlyBadLinesIsNotReported() throws Exception {
        Files.writeString(root.resolve("stale.tsv"), "cli-tapes\n");
        env.put(StaleOutputsReportKind.FILE, "stale.tsv");

        assertThat(kind.collect(Fixtures.step(root), List.of())).isEmpty();
        assertThat(warnings).hasSize(1);
    }

    // --- highlights ------------------------------------------------------------------------------

    @Test
    void eachKindIsOneBadHighlightNamingItsCommand() {
        StaleOutputs report = new StaleOutputs(List.of(new StaleOutputs.Kind("cli-tapes", "regen tapes"),
                new StaleOutputs.Kind("entity-diagram", "regen diagram")));

        List<Highlight> highlights = kind.highlight(report, Optional.empty(), Fixtures.step(root));

        assertThat(highlights).containsExactly(
                new Highlight(Highlight.BAD, "stale outputs: cli-tapes — regenerate with: regen tapes", null, null, null),
                new Highlight(Highlight.BAD, "stale outputs: entity-diagram — regenerate with: regen diagram", null,
                        null, null));
    }

    @Test
    void aLongCommandIsCutToTheHighlightLimit() {
        StaleOutputs report = new StaleOutputs(List.of(new StaleOutputs.Kind("cli-tapes", TAPES)));

        Highlight highlight = kind.highlight(report, Optional.empty(), Fixtures.step(root)).getFirst();

        assertThat(highlight.text()).hasSize(Highlight.MAX_TEXT)
                .startsWith("stale outputs: cli-tapes — regenerate with: qits release-request").endsWith("…");
    }

    @Test
    void atMostTenHighlights() {
        StaleOutputs report = new StaleOutputs(IntStream.range(0, 12)
                .mapToObj(i -> new StaleOutputs.Kind("kind-" + i, "regen " + i)).toList());

        List<Highlight> highlights = kind.highlight(report, Optional.empty(), Fixtures.step(root));

        assertThat(highlights).hasSize(Highlight.MAX_PER_REPORT);
        assertThat(highlights.getLast().text()).isEqualTo("stale outputs: kind-9 — regenerate with: regen 9");
        assertThat(kind.describe(report)).startsWith("12 stale: kind-0, kind-1");
    }
}
