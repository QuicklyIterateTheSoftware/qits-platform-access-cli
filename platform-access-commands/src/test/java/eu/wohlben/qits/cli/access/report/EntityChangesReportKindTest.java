package eu.wohlben.qits.cli.access.report;

import eu.wohlben.qits.cli.access.database.MermaidEntityDiagramFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityChangesReportKind} over fixture trees in a throwaway git repository: the baseline's
 * files committed and tagged, the fold's committed on top, the step's root at the fold.
 */
class EntityChangesReportKindTest {

    static final String VERSION = "2026.1003.52637";
    static final Baseline BASELINE = new Baseline(VERSION, "run-0", "rr-0", "f00d");

    static final String HEADER = MermaidEntityDiagramFormat.HEADER_PREFIX + " (jpa). Do not edit: the entity-diagram "
            + "automation rewrites this file on every release request fold. -->";

    static final String RUN = table("ci_run", "uuid id PK \"not null\"", "string state \"not null, length 32\"");
    static final String STEP = table("ci_step", "uuid id PK \"not null\"", "int position \"not null\"",
            "string run_id FK \"not null\"", "string title \"length 200\"");

    @TempDir
    Path work;

    private Path root;
    private final List<String> warnings = new ArrayList<>();
    private final EntityChangesReportKind kind = new EntityChangesReportKind(warnings::add);

    @BeforeEach
    void repository() throws Exception {
        root = Files.createDirectories(work.resolve("repo"));
        GitChangedLinesTest.git(root, "init", "--quiet", "--initial-branch=main");
    }

    // --- the diff --------------------------------------------------------------------------------

    @Test
    void theSameDiagramIsUnchangedAndSaysSoInOneGoodLine() throws Exception {
        String ci = diagram("ci", RUN + STEP);
        EntityChanges report = collect(Map.of("ci.md", ci), Map.of("ci.md", ci)).orElseThrow();

        assertThat(report.baseline()).isEqualTo(new EntityChanges.BaselineSide(VERSION, "f00d", true));
        assertThat(report.truncated()).isFalse();
        EntityChanges.Unit unit = report.units().getFirst();
        assertThat(unit.file()).isEqualTo("docs/database/ci.md");
        assertThat(unit.unit()).isEqualTo("ci");
        assertThat(unit.status()).isEqualTo(EntityChanges.UNCHANGED);
        assertThat(unit.tables()).isEmpty();
        assertThat(unit.before()).as("before only for CHANGED and REMOVED").isNull();
        assertThat(unit.after()).startsWith("erDiagram\n").contains("ci_run {").doesNotContain("```");
        assertThat(highlights(report)).containsExactly("good: Entities unchanged since " + VERSION);
        assertThat(kind.describe(report)).isEqualTo("1 unit, 0 changed");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anAddedTableListsItsColumns() throws Exception {
        String report = table("ci_report", "uuid id PK \"not null\"", "string kind \"not null, length 64\"",
                "text payload \"not null, lob\"");
        EntityChanges changes = collect(Map.of("ci.md", diagram("ci", RUN)),
                Map.of("ci.md", diagram("ci", report + RUN))).orElseThrow();

        EntityChanges.Unit unit = changes.units().getFirst();
        assertThat(unit.status()).isEqualTo(EntityChanges.CHANGED);
        assertThat(unit.tables()).containsExactly(new EntityChanges.Table("ci_report", EntityChanges.ADDED, "ci",
                new EntityChanges.Columns(List.of("id: uuid, not null, PK", "kind: string, not null, 64",
                        "payload: text, not null, lob"), List.of(), List.of())));
        assertThat(unit.before()).contains("ci_run {").doesNotContain("ci_report");
        assertThat(unit.after()).contains("ci_report {");
        assertThat(highlights(changes)).containsExactly("warn: Entities changed since " + VERSION
                + ": +1 ~0 \u22120 tables");
    }

    @Test
    void columnsAddedRemovedAndNarrowedAreNamedAndCounted() throws Exception {
        String before = table("ci_step", "uuid id PK \"not null\"", "int position \"not null\"",
                "string run_id FK \"not null\"", "string title \"length 200\"", "string note",
                "string kind \"length 64\"");
        String after = table("ci_step", "uuid id PK \"not null\"", "long position \"not null\"",
                "string run_id FK \"not null\"", "string title \"length 100\"", "string kind \"not null, length 64\"",
                "boolean hidden \"not null\"");
        EntityChanges changes = collect(Map.of("ci.md", diagram("ci", RUN + before)),
                Map.of("ci.md", diagram("ci", RUN + after))).orElseThrow();

        assertThat(changes.units().getFirst().tables()).containsExactly(new EntityChanges.Table("ci_step",
                EntityChanges.CHANGED, "ci", new EntityChanges.Columns(
                        List.of("hidden: boolean, not null"),
                        List.of("note"),
                        List.of(new EntityChanges.ColumnChange("kind", "string, null, 64", "string, not null, 64"),
                                new EntityChanges.ColumnChange("position", "int, not null", "long, not null"),
                                new EntityChanges.ColumnChange("title", "string, null, 200", "string, null, 100")))));
        assertThat(highlights(changes)).containsExactly(
                "warn: Entities changed since " + VERSION + ": +0 ~1 \u22120 tables",
                "warn: Columns removed or narrowed: 4");
    }

    @Test
    void aWiderOrNullableColumnIsChangedButNotNarrowed() throws Exception {
        EntityChanges changes = collect(
                Map.of("ci.md", diagram("ci", table("ci_run", "uuid id PK \"not null\"",
                        "string state \"not null, length 32\""))),
                Map.of("ci.md", diagram("ci", table("ci_run", "uuid id PK \"not null\"",
                        "string state \"length 64\"")))).orElseThrow();

        assertThat(changes.units().getFirst().tables().getFirst().columns().changed()).containsExactly(
                new EntityChanges.ColumnChange("state", "string, not null, 32", "string, null, 64"));
        assertThat(highlights(changes)).containsExactly("warn: Entities changed since " + VERSION
                + ": +0 ~1 \u22120 tables");
    }

    @Test
    void anAddedRelationIsItsRenderedLine() throws Exception {
        EntityChanges changes = collect(Map.of("ci.md", diagram("ci", RUN + STEP)),
                Map.of("ci.md", diagram("ci", RUN + STEP + "  ci_step }o--|| ci_run : \"run_id\"\n"))).orElseThrow();

        EntityChanges.Unit unit = changes.units().getFirst();
        assertThat(unit.status()).isEqualTo(EntityChanges.CHANGED);
        assertThat(unit.tables()).isEmpty();
        assertThat(unit.relations()).isEqualTo(new EntityChanges.Relations(
                List.of("ci_step }o--|| ci_run : run_id"), List.of()));
        assertThat(highlights(changes)).containsExactly("warn: Entities changed since " + VERSION
                + ": +0 ~0 \u22120 tables");
    }

    @Test
    void aUnitFileAddedAndOneRemoved() throws Exception {
        String artifacts = diagram("artifacts", table("artifact", "uuid id PK \"not null\""));
        String epics = diagram("epics", table("epic", "uuid id PK \"not null\"", "string title \"not null\"")
                + table("epic_task", "uuid id PK \"not null\""));
        EntityChanges changes = collect(Map.of("artifacts.md", artifacts, "ci.md", diagram("ci", RUN)),
                Map.of("ci.md", diagram("ci", RUN), "epics.md", epics)).orElseThrow();

        assertThat(changes.units()).extracting(EntityChanges.Unit::file, EntityChanges.Unit::unit,
                        EntityChanges.Unit::status)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("docs/database/artifacts.md", "artifacts",
                                EntityChanges.REMOVED),
                        org.assertj.core.groups.Tuple.tuple("docs/database/ci.md", "ci", EntityChanges.UNCHANGED),
                        org.assertj.core.groups.Tuple.tuple("docs/database/epics.md", "epics", EntityChanges.ADDED));
        EntityChanges.Unit removed = changes.units().get(0);
        assertThat(removed.before()).contains("artifact {");
        assertThat(removed.after()).isNull();
        assertThat(removed.tables()).containsExactly(new EntityChanges.Table("artifact", EntityChanges.REMOVED, "ci",
                new EntityChanges.Columns(List.of(), List.of("id"), List.of())));
        EntityChanges.Unit added = changes.units().get(2);
        assertThat(added.before()).isNull();
        assertThat(added.after()).contains("epic {");
        assertThat(added.tables()).extracting(EntityChanges.Table::name, EntityChanges.Table::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("epic", EntityChanges.ADDED),
                        org.assertj.core.groups.Tuple.tuple("epic_task", EntityChanges.ADDED));
        assertThat(highlights(changes)).containsExactly("warn: Entities changed since " + VERSION
                + ": +2 ~0 \u22121 tables");
        assertThat(kind.describe(changes)).isEqualTo("3 units, 2 changed");
    }

    // --- no baseline, or none to compare with ----------------------------------------------------

    @Test
    void noBaselineMakesEveryUnitCurrent() throws Exception {
        write(Map.of("ci.md", diagram("ci", RUN + STEP), "epics.md", diagram("epics", table("epic",
                "uuid id PK \"not null\""))));
        commit("the fold");

        EntityChanges report = kind.collect(step(0, Optional.empty(), BaselineTag.none()), List.of()).orElseThrow();

        assertThat(report.baseline()).isNull();
        assertThat(report.units()).extracting(EntityChanges.Unit::status)
                .containsExactly(EntityChanges.CURRENT, EntityChanges.CURRENT);
        EntityChanges.Unit ci = report.units().getFirst();
        assertThat(ci.tables()).extracting(EntityChanges.Table::name, EntityChanges.Table::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ci_run", EntityChanges.CURRENT),
                        org.assertj.core.groups.Tuple.tuple("ci_step", EntityChanges.CURRENT));
        assertThat(ci.before()).isNull();
        assertThat(ci.after()).startsWith("erDiagram\n");
        assertThat(highlights(report)).containsExactly("info: Entity diagram is new: 3 tables in 2 units");
        assertThat(kind.describe(report)).isEqualTo("2 units, new");
        assertThat(warnings).isEmpty();
    }

    @Test
    void aBaselineTagWithoutDocsDatabaseMakesEveryUnitCurrent() throws Exception {
        Files.writeString(root.resolve("README.md"), "before the diagrams\n");
        commit("the baseline");
        tag();
        write(Map.of("ci.md", diagram("ci", RUN)));
        commit("the fold");

        EntityChanges report = kind.collect(step(0), List.of()).orElseThrow();

        assertThat(report.baseline()).isEqualTo(new EntityChanges.BaselineSide(VERSION, "f00d", false));
        assertThat(report.units()).singleElement().extracting(EntityChanges.Unit::status)
                .isEqualTo(EntityChanges.CURRENT);
        assertThat(highlights(report)).containsExactly("info: Entity diagram is new: 1 table in 1 unit");
    }

    @Test
    void aBaselineTagThatCannotBeHadIsNoBaselineWithOneWarning() throws Exception {
        write(Map.of("ci.md", diagram("ci", RUN)));
        commit("the fold");

        EntityChanges report = kind.collect(step(0), List.of()).orElseThrow();

        assertThat(report.baseline()).isNull();
        assertThat(report.units()).singleElement().extracting(EntityChanges.Unit::status)
                .isEqualTo(EntityChanges.CURRENT);
        assertThat(warnings).singleElement().asString().startsWith("baseline tag " + VERSION + ": not in this tree");
    }

    @Test
    void onlyStepZeroReports() throws Exception {
        write(Map.of("ci.md", diagram("ci", RUN)));
        commit("the fold");

        assertThat(kind.collect(step(1, Optional.empty(), BaselineTag.none()), List.of())).isEmpty();
        assertThat(kind.collect(step(2, Optional.empty(), BaselineTag.none()), List.of())).isEmpty();
    }

    @Test
    void noGeneratedFileOnEitherSideIsNotReported() throws Exception {
        Files.writeString(root.resolve("README.md"), "nothing\n");
        commit("the baseline");
        tag();
        Files.createDirectories(root.resolve("docs/database"));
        Files.writeString(root.resolve("docs/database/README.md"), "# Hand-written\n");
        commit("the fold");

        assertThat(kind.collect(step(0), List.of())).isEmpty();
        assertThat(kind.collect(step(0, Optional.empty(), BaselineTag.none()), List.of())).isEmpty();
    }

    @Test
    void aHandWrittenFileWithoutTheHeaderIsIgnored() throws Exception {
        String ci = diagram("ci", RUN);
        String readme = "# The database\n\n```mermaid\nerDiagram\n  hand_drawn {\n    int id PK\n  }\n```\n";
        EntityChanges report = collect(Map.of("ci.md", ci, "README.md", readme),
                Map.of("ci.md", ci, "README.md", readme + "\nMore words.\n")).orElseThrow();

        assertThat(report.units()).extracting(EntityChanges.Unit::file).containsExactly("docs/database/ci.md");
        assertThat(report.units().getFirst().status()).isEqualTo(EntityChanges.UNCHANGED);
    }

    @Test
    void aFileThatDoesNotParseIsAChangedUnitWithItsRawTextsAndANote() throws Exception {
        String broken = HEADER + "\n# Persistence unit `ci`\n\nno diagram here\n";
        EntityChanges report = collect(Map.of("ci.md", diagram("ci", RUN)), Map.of("ci.md", broken)).orElseThrow();

        EntityChanges.Unit unit = report.units().getFirst();
        assertThat(unit.status()).isEqualTo(EntityChanges.CHANGED);
        assertThat(unit.unit()).isEqualTo("ci");
        assertThat(unit.before()).startsWith("erDiagram\n");
        assertThat(unit.after()).isEqualTo(broken);
        assertThat(unit.notes()).containsExactly("docs/database/ci.md does not parse as an entity diagram");
        assertThat(highlights(report)).containsExactly("warn: Entities changed since " + VERSION
                + ": +0 ~0 \u22120 tables");
    }

    @Test
    void aChangeOnlyToTheDescriptionIsChangedWithANote() throws Exception {
        String before = diagram("ci", RUN);
        String after = before.replace("1 entities", "one entity");
        EntityChanges report = collect(Map.of("ci.md", before), Map.of("ci.md", after)).orElseThrow();

        EntityChanges.Unit unit = report.units().getFirst();
        assertThat(unit.status()).isEqualTo(EntityChanges.CHANGED);
        assertThat(unit.tables()).isEmpty();
        assertThat(unit.notes()).containsExactly("Only the unit's description changed: summary");
    }

    // --- the size cap ----------------------------------------------------------------------------

    @Test
    void aboveOneMebibyteTheUnchangedDiagramsGoFirst() throws Exception {
        String big = diagram("big", tables("big", 4_000));
        String other = diagram("other", tables("other", 4_000));
        String ci = diagram("ci", RUN);
        EntityChanges report = collect(Map.of("big.md", big, "other.md", other, "ci.md", ci),
                Map.of("big.md", big, "other.md", other, "ci.md", diagram("ci", RUN + STEP))).orElseThrow();

        assertThat(report.truncated()).isTrue();
        assertThat(EntityChangesReportKind.size(report)).isLessThanOrEqualTo(EntityChangesReportKind.MAX_PAYLOAD_BYTES);
        EntityChanges.Unit changed = report.units().stream().filter(u -> u.unit().equals("ci")).findFirst()
                .orElseThrow();
        assertThat(changed.before()).isNotNull();
        assertThat(changed.after()).as("a changed unit keeps its texts").isNotNull();
        assertThat(report.units()).filteredOn(u -> u.status().equals(EntityChanges.UNCHANGED))
                .extracting(EntityChanges.Unit::after).containsNull();
    }

    @Test
    void aboveOneMebibyteWithoutABaselineTheCurrentDiagramsGo() throws Exception {
        write(Map.of("big.md", diagram("big", tables("big", 4_000)), "other.md",
                diagram("other", tables("other", 4_000)), "ci.md", diagram("ci", RUN)));
        commit("the fold");

        EntityChanges report = kind.collect(step(0, Optional.empty(), BaselineTag.none()), List.of()).orElseThrow();

        assertThat(report.truncated()).isTrue();
        assertThat(EntityChangesReportKind.size(report)).isLessThanOrEqualTo(EntityChangesReportKind.MAX_PAYLOAD_BYTES);
        assertThat(report.units()).filteredOn(u -> u.unit().equals("ci")).singleElement()
                .extracting(EntityChanges.Unit::after).isNotNull();
        assertThat(highlights(report)).containsExactly("info: Entity diagram is new: 8001 tables in 3 units");
    }

    @Test
    void underOneMebibyteNothingIsCut() throws Exception {
        String ci = diagram("ci", RUN);
        EntityChanges report = collect(Map.of("ci.md", ci), Map.of("ci.md", ci)).orElseThrow();

        assertThat(EntityChangesReportKind.capped(report)).isSameAs(report);
    }

    // --- the payload and its highlights ----------------------------------------------------------

    @Test
    void thePayloadReadsBackAsItWasWritten() throws Exception {
        EntityChanges report = collect(Map.of("ci.md", diagram("ci", RUN)),
                Map.of("ci.md", diagram("ci", RUN + STEP))).orElseThrow();

        String json = ReportJson.MAPPER.writeValueAsString(report);

        assertThat(ReportJson.MAPPER.readValue(json, EntityChanges.class)).isEqualTo(report);
        assertThat(ReportJson.MAPPER.readTree(json).fieldNames()).toIterable()
                .containsExactly("baseline", "units", "truncated");
        assertThat(ReportJson.MAPPER.readTree(json).path("units").get(0).fieldNames()).toIterable()
                .containsExactly("file", "unit", "status", "tables", "relations", "before", "after", "notes");
    }

    @Test
    void highlightsFitEightyCharactersAndTheirSeverities() {
        String longVersion = "2026.1231.235959";
        EntityChanges.Table changed = new EntityChanges.Table("t", EntityChanges.CHANGED, "m",
                new EntityChanges.Columns(List.of(), List.of("a", "b"), List.of()));
        EntityChanges report = new EntityChanges(new EntityChanges.BaselineSide(longVersion, null, true),
                List.of(new EntityChanges.Unit("docs/database/u.md", "u", EntityChanges.CHANGED,
                        List.of(changed, changed, changed), EntityChanges.Relations.none(), "b", "a", List.of())),
                false);

        List<Highlight> highlights = kind.highlight(report, Optional.empty(), step(0));

        assertThat(highlights).extracting(Highlight::severity).containsExactly(Highlight.WARN, Highlight.WARN);
        assertThat(highlights).extracting(Highlight::text).containsExactly(
                "Entities changed since 2026.1231.235959: +0 ~3 \u22120 tables", "Columns removed or narrowed: 6");
        assertThat(highlights).allSatisfy(h -> assertThat(h.text()).hasSizeLessThanOrEqualTo(Highlight.MAX_TEXT)
                .doesNotEndWith("\u2026"));
    }

    @Test
    void theKindIsRegisteredWithoutAParser() {
        ReportKinds kinds = ReportKinds.standard(warnings::add);

        assertThat(kinds.kinds()).extracting(ReportKind::id).contains("entity-changes");
        assertThat(kinds.parsersOf("entity-changes")).isEmpty();
        assertThat(kind.version()).isEqualTo(1);
        assertThat(kind.payloadType()).isEqualTo(EntityChanges.class);
    }

    // --- fixtures --------------------------------------------------------------------------------

    /** A generated file, as {@code qits database diagram} writes one, around {@code body}. */
    static String diagram(String unit, String body) {
        int entities = body.split("\\{\n", -1).length - 1;
        return HEADER + "\n# Persistence unit `" + unit + "`\n\nDatasource `" + unit + "` \u00b7 package `fx."
                + unit + "` \u00b7 " + entities + " entities\n\n```mermaid\nerDiagram\n" + body + "```\n";
    }

    /** One table with its {@code %%} line, origin {@code ci}. */
    static String table(String name, String... columns) {
        StringBuilder out = new StringBuilder("  %% ").append(name).append(": fx.").append(name).append(" (ci)\n")
                .append("  ").append(name).append(" {\n");
        for (String column : columns) {
            out.append("    ").append(column).append('\n');
        }
        return out.append("  }\n").toString();
    }

    private static String tables(String prefix, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            out.append(table(String.format("%s_table_%05d", prefix, i), "uuid id PK \"not null\"",
                    "string a_rather_long_column_name_for_size \"not null, length 255\""));
        }
        return out.toString();
    }

    /** The baseline's files committed and tagged, the fold's committed on top; a step at the fold. */
    private Optional<EntityChanges> collect(Map<String, String> baseline, Map<String, String> fold) throws Exception {
        write(baseline);
        commit("the baseline");
        tag();
        try (var files = Files.list(root.resolve("docs/database"))) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        write(fold);
        commit("the fold");
        return kind.collect(step(0), List.of());
    }

    private void write(Map<String, String> files) throws IOException {
        Path directory = Files.createDirectories(root.resolve("docs/database"));
        for (Map.Entry<String, String> file : files.entrySet()) {
            Files.writeString(directory.resolve(file.getKey()), file.getValue());
        }
    }

    private void commit(String message) throws Exception {
        GitChangedLinesTest.git(root, "add", "-A");
        Git.Result committed = GitChangedLinesTest.git(root, "commit", "--quiet", "--allow-empty", "-m", message);
        assertThat(committed.exit()).as(committed.err()).isZero();
    }

    private void tag() throws Exception {
        assertThat(GitChangedLinesTest.git(root, "tag", VERSION).exit()).isZero();
    }

    private StepContext step(int index) {
        return step(index, Optional.of(BASELINE), BaselineTag.in(root, Optional.of(BASELINE), null, warnings::add));
    }

    private StepContext step(int index, Optional<Baseline> baseline, BaselineTag tag) {
        return new StepContext(root, "run-1", index, Fixtures.REPOSITORY, Fixtures.COMMIT, 0, baseline,
                ChangedLines.unavailable(), TestCaseLocators.registered(), tag);
    }

    private List<String> highlights(EntityChanges report) {
        List<Highlight> highlights = kind.highlight(report, Optional.empty(), step(0));
        assertThat(highlights).hasSizeLessThanOrEqualTo(Highlight.MAX_PER_REPORT)
                .allSatisfy(h -> assertThat(h.text().length()).isLessThanOrEqualTo(Highlight.MAX_TEXT));
        return highlights.stream().map(h -> h.severity() + ": " + h.text()).toList();
    }
}
