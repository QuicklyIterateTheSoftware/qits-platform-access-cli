package eu.wohlben.qits.cli.access.report.screenshots;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.report.Baseline;
import eu.wohlben.qits.cli.access.report.BaselineTag;
import eu.wohlben.qits.cli.access.report.ChangedLines;
import eu.wohlben.qits.cli.access.report.Git;
import eu.wohlben.qits.cli.access.report.Highlight;
import eu.wohlben.qits.cli.access.report.ReportJson;
import eu.wohlben.qits.cli.access.report.ReportKind;
import eu.wohlben.qits.cli.access.report.ReportKinds;
import eu.wohlben.qits.cli.access.report.RepositoryRef;
import eu.wohlben.qits.cli.access.report.StepContext;
import eu.wohlben.qits.cli.access.report.TestCaseLocators;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link ScreenshotsReportKind} and {@link VitestBrowserScreenshots} against throwaway repositories:
 * an "origin" with the baseline's tag, and the step's tree cloned from it shallow, at the fold, without
 * the tag, the way qits-ci-daemon clones.
 */
class ScreenshotsReportKindTest {

    static final String BASELINE = "2026.1006.93346";
    static final String REPO_ID = "488363a1-6ba0-4101-948a-14a8bfcb9e79";
    static final RepositoryRef REPOSITORY = new RepositoryRef("8f1c2d3e-0000-4000-8000-000000000001", "qits-fx-app");

    static final String A = "src/app/a/__screenshots__/a.browser.spec.ts/one-chromium-linux.png";
    static final String A_MOVED = "src/app/a2/__screenshots__/a.browser.spec.ts/one-chromium-linux.png";
    static final String B = "src/app/b/__screenshots__/b.browser.spec.ts/two-chromium-linux.png";
    static final String C = "src/app/c/__screenshots__/c.browser.spec.ts/three-chromium-linux.png";
    static final String D = "src/app/d/__screenshots__/d.browser.spec.ts/four-firefox-darwin.png";
    static final String E = "src/app/e/__screenshots__/e.browser.spec.ts/five-chromium-linux.png";
    static final String RENDERER = "src/testing/browser/renderer.txt";

    @TempDir
    Path work;

    private final List<String> warnings = new ArrayList<>();
    private final VitestBrowserScreenshots vitest = new VitestBrowserScreenshots(Path.of("/nonexistent/provenance"));

    // --- the convention --------------------------------------------------------------------------

    @Test
    void identifyReadsTheRealLandingAppShapes() {
        assertThat(vitest.identify(
                "src/app/layout/shell/__screenshots__/shell.layout.browser.spec.ts/narrow-open-chromium-linux.png"))
                .contains(new ScreenshotId("src/app/layout/shell/shell.layout.browser.spec.ts", "narrow-open",
                        "chromium", "linux"));
        assertThat(vitest.identify(
                "src/app/work/__screenshots__/work.browser.spec.ts/work-item-narrow-expanded-chromium-linux.png"))
                .as("a name with dashes keeps them")
                .contains(new ScreenshotId("src/app/work/work.browser.spec.ts", "work-item-narrow-expanded",
                        "chromium", "linux"));
        assertThat(vitest.identify("__screenshots__/top.browser.spec.ts/x-webkit-win32.png"))
                .as("a spec at the root")
                .contains(new ScreenshotId("top.browser.spec.ts", "x", "webkit", "win32"));
        assertThat(vitest.identify("src/__screenshots__/s.spec.ts/just-a-name-edge-linux.png"))
                .as("an unsplittable stem is the name")
                .contains(new ScreenshotId("src/s.spec.ts", "just-a-name-edge-linux", null, null));
        assertThat(vitest.identify("src/__screenshots__/s.spec.ts/narrow-chromium-linux.jpg")).as("not a PNG").isEmpty();
        assertThat(vitest.identify("src/__screenshots__/narrow-chromium-linux.png"))
                .as("directly under __screenshots__").isEmpty();
        assertThat(vitest.identify("src/__screenshots__/s.spec.ts/deeper/x-chromium-linux.png")).isEmpty();
        assertThat(vitest.identify("src/app/logo.png")).isEmpty();
    }

    @Test
    void renderedHereIsTheProvenanceRecordOrTheRunRecord() throws IOException {
        Path root = Files.createDirectories(work.resolve("root"));
        Path provenance = work.resolve("qits-renderer-provenance");

        assertThat(new VitestBrowserScreenshots(provenance).renderedHere(root)).as("neither").isFalse();

        Files.writeString(provenance, "chromium=152\n");
        assertThat(new VitestBrowserScreenshots(provenance).renderedHere(root)).as("the renderer image").isTrue();

        Files.delete(provenance);
        write(root, VitestBrowserScreenshots.RUN_RECORD, "{}");
        assertThat(new VitestBrowserScreenshots(provenance).renderedHere(root)).as("the run record").isTrue();
    }

    @Test
    void theRendererRecordIsTheOneRendererTxt() {
        assertThat(vitest.rendererRecord(Set.of("README.md", RENDERER))).contains(RENDERER);
        assertThat(vitest.rendererRecord(Set.of("testing/browser/renderer.txt"))).contains("testing/browser/renderer.txt");
        assertThat(vitest.rendererRecord(Set.of(RENDERER, "other/src/testing/browser/renderer.txt"))).isEmpty();
        assertThat(vitest.rendererRecord(Set.of("src/xtesting/browser/renderer.txt"))).isEmpty();
    }

    // --- collect ---------------------------------------------------------------------------------

    @Test
    void theFoldIsComparedWithTheBaselineTagPathByPathAndBlobByBlob() throws Exception {
        Path origin = origin();
        Path step = shallowClone(origin);

        ScreenshotsReport report = kind().collect(step(step, Optional.of(baseline()), url(origin)), List.of())
                .orElseThrow();

        assertThat(warnings).isEmpty();
        assertThat(report.repositoryId()).isEqualTo(REPO_ID);
        assertThat(report.headSha()).isEqualTo(rev(origin, "HEAD"));
        assertThat(report.baseline()).isEqualTo(new ScreenshotsReport.BaselineSide(BASELINE, rev(origin, BASELINE + "^{commit}")));
        assertThat(report.conventions()).containsExactly(new ScreenshotsReport.Convention("vitest-browser", "vitest"));
        assertThat(report.totals()).isEqualTo(new ScreenshotsReport.Totals(4, 2, 1, 2, 1));
        assertThat(report.truncated()).isFalse();
        assertThat(report.entries()).extracting(ScreenshotsReport.Entry::status, ScreenshotsReport.Entry::path)
                .containsExactly(
                        tuple("NEW", A_MOVED),
                        tuple("NEW", D),
                        tuple("CHANGED", B),
                        tuple("REMOVED", A),
                        tuple("REMOVED", C));

        ScreenshotsReport.Entry moved = report.entries().get(0);
        assertThat(moved.movedFrom()).isEqualTo(A);
        assertThat(moved.afterBlob()).isEqualTo(rev(origin, "HEAD:" + A_MOVED)).isEqualTo(rev(origin, BASELINE + ":" + A));
        assertThat(moved.beforeBlob()).isNull();
        assertThat(moved.afterBytes()).isEqualTo(5L);
        ScreenshotsReport.Entry added = report.entries().get(1);
        assertThat(added).isEqualTo(new ScreenshotsReport.Entry("NEW", D, "vitest-browser",
                "src/app/d/d.browser.spec.ts", "four", "firefox", "darwin", null, rev(origin, "HEAD:" + D), null,
                6L, null, null));
        ScreenshotsReport.Entry changed = report.entries().get(2);
        assertThat(changed.beforeBlob()).isEqualTo(rev(origin, BASELINE + ":" + B));
        assertThat(changed.afterBlob()).isEqualTo(rev(origin, "HEAD:" + B));
        assertThat(changed.beforeBytes()).isEqualTo(5L);
        assertThat(changed.afterBytes()).isEqualTo(8L);
        assertThat(changed.spec()).isEqualTo("src/app/b/b.browser.spec.ts");
        assertThat(changed.name()).isEqualTo("two");
        assertThat(changed.movedFrom()).isNull();
        assertThat(changed.movedTo()).isNull();
        ScreenshotsReport.Entry gone = report.entries().get(3);
        assertThat(gone.movedTo()).isEqualTo(A_MOVED);
        assertThat(gone.afterBlob()).isNull();
        assertThat(report.entries().get(4).movedTo()).as("C's image went nowhere").isNull();

        assertThat(report.renderer()).isEqualTo(new ScreenshotsReport.Renderer(RENDERER, true, List.of(
                new ScreenshotsReport.RendererEntry("chromium", "Google Chrome for Testing 152.0", "Google Chrome for Testing 153.0"),
                new ScreenshotsReport.RendererEntry("libharfbuzz0b", null, "8.3.0"))));

        List<Highlight> highlights = kind().highlight(report, Optional.empty(), null);
        assertThat(highlights).containsExactly(
                new Highlight("info", "2 new, 1 changed, 2 removed screenshots", "screenshots.changes", 5.0, null),
                new Highlight("warn", "renderer changed (chromium, libharfbuzz0b): diffs may be renderer noise",
                        null, null, null));
    }

    @Test
    void exactMovesPairOneToOneInPathOrder() throws Exception {
        Path origin = init("pairs");
        write(origin, "x/__screenshots__/a.spec.ts/one.png", "same");
        write(origin, "x/__screenshots__/b.spec.ts/one.png", "same");
        commit(origin, "baseline");
        must(origin, "tag", BASELINE);
        Files.delete(origin.resolve("x/__screenshots__/a.spec.ts/one.png"));
        Files.delete(origin.resolve("x/__screenshots__/b.spec.ts/one.png"));
        write(origin, "y/__screenshots__/a.spec.ts/one.png", "same");
        write(origin, "y/__screenshots__/b.spec.ts/one.png", "same");
        write(origin, "y/__screenshots__/c.spec.ts/one.png", "same");
        commit(origin, "fold");
        renderedHere(origin);

        ScreenshotsReport report = kind().collect(step(origin, Optional.of(baseline()), null), List.of()).orElseThrow();

        assertThat(report.entries()).extracting(ScreenshotsReport.Entry::path, ScreenshotsReport.Entry::movedFrom,
                ScreenshotsReport.Entry::movedTo).containsExactly(
                tuple("y/__screenshots__/a.spec.ts/one.png", "x/__screenshots__/a.spec.ts/one.png", null),
                tuple("y/__screenshots__/b.spec.ts/one.png", "x/__screenshots__/b.spec.ts/one.png", null),
                tuple("y/__screenshots__/c.spec.ts/one.png", null, null),
                tuple("x/__screenshots__/a.spec.ts/one.png", null, "y/__screenshots__/a.spec.ts/one.png"),
                tuple("x/__screenshots__/b.spec.ts/one.png", null, "y/__screenshots__/b.spec.ts/one.png"));
        assertThat(report.renderer()).as("no record on either side").isNull();
    }

    @Test
    void withoutABaselineEverythingIsNew() throws Exception {
        Path origin = origin();
        Path step = shallowClone(origin);

        ScreenshotsReport report = kind().collect(step(step, Optional.empty(), url(origin)), List.of()).orElseThrow();

        assertThat(report.baseline()).isNull();
        assertThat(report.renderer()).isNull();
        assertThat(report.totals()).isEqualTo(new ScreenshotsReport.Totals(4, 4, 0, 0, 0));
        assertThat(report.entries()).extracting(ScreenshotsReport.Entry::status).containsOnly("NEW");
        assertThat(report.entries()).extracting(ScreenshotsReport.Entry::path).containsExactly(A_MOVED, B, D, E);
        assertThat(report.entries()).allSatisfy(e -> assertThat(e.movedFrom()).isNull());
        assertThat(kind().highlight(report, Optional.empty(), null)).containsExactly(
                new Highlight("info", "4 screenshots, no baseline to compare", null, null, null));
        assertThat(kind().describe(report)).isEqualTo("4 new, 0 changed, 0 removed");
        assertThat(warnings).isEmpty();
    }

    @Test
    void anUnknownTagIsNoBaselineWithOneWarningNeverThrown() throws Exception {
        Path origin = origin();
        Path step = shallowClone(origin);

        ScreenshotsReport report = kind().collect(step(step, Optional.of(new Baseline("2025.101.1", "run-0", null, null)),
                url(origin)), List.of()).orElseThrow();

        assertThat(report.baseline()).isNull();
        assertThat(report.renderer()).isNull();
        assertThat(report.totals().added()).isEqualTo(4);
        assertThat(warnings).singleElement().asString().startsWith("baseline tag 2025.101.1: could not be fetched");
    }

    @Test
    void notRenderedHereIsNotReported() throws Exception {
        Path origin = origin();
        Path step = shallowClone(origin);
        Files.delete(step.resolve(VitestBrowserScreenshots.RUN_RECORD));
        ScreenshotsReportKind kind = kind();

        assertThat(kind.collect(step(step, Optional.of(baseline()), url(origin)), List.of())).isEmpty();
        assertThat(kind.notReported()).isEqualTo("not rendered in this step");
        assertThat(git(step, "tag", "--list").text()).as("nothing was fetched").isEmpty();
    }

    @Test
    void toolingWithoutBaselinesIsNotReported() throws Exception {
        Path origin = init("tooling");
        write(origin, RENDERER, "chromium=152\n");
        write(origin, "src/app/logo.png", "png");
        commit(origin, "baseline");
        must(origin, "tag", BASELINE);
        write(origin, RENDERER, "chromium=153\n");
        commit(origin, "fold");
        renderedHere(origin);
        ScreenshotsReportKind kind = kind();

        assertThat(kind.collect(step(origin, Optional.of(baseline()), null), List.of())).isEmpty();
        assertThat(kind.notReported()).isEqualTo("no screenshot baselines");
    }

    @Test
    void unchangedScreenshotsSayUnchanged() throws Exception {
        Path origin = init("unchanged");
        write(origin, A, "image");
        write(origin, RENDERER, "chromium=152\n");
        commit(origin, "baseline");
        must(origin, "tag", BASELINE);
        write(origin, "README.md", "a change elsewhere\n");
        commit(origin, "fold");
        renderedHere(origin);

        ScreenshotsReport report = kind().collect(step(origin, Optional.of(baseline()), null), List.of()).orElseThrow();

        assertThat(report.entries()).isEmpty();
        assertThat(report.totals()).isEqualTo(new ScreenshotsReport.Totals(1, 0, 0, 0, 1));
        assertThat(report.renderer()).isEqualTo(new ScreenshotsReport.Renderer(RENDERER, false, List.of()));
        assertThat(kind().highlight(report, Optional.empty(), null)).containsExactly(
                new Highlight("good", "screenshots unchanged (1)", null, null, null));
    }

    @Test
    void theEntriesAreCappedAndTheTotalsAreNot() throws Exception {
        Path origin = init("many");
        for (int i = 0; i < 1001; i++) {
            write(origin, "src/__screenshots__/many.spec.ts/shot-%04d-chromium-linux.png".formatted(i), "image " + i);
        }
        commit(origin, "fold");
        renderedHere(origin);

        ScreenshotsReport report = kind().collect(step(origin, Optional.empty(), null), List.of()).orElseThrow();

        assertThat(report.truncated()).isTrue();
        assertThat(report.entries()).hasSize(ScreenshotsReportKind.MAX_ENTRIES);
        assertThat(report.entries().getLast().path()).contains("shot-0999-");
        assertThat(report.totals()).isEqualTo(new ScreenshotsReport.Totals(1001, 1001, 0, 0, 0));
    }

    // --- parts -----------------------------------------------------------------------------------

    @Test
    void theTreeListingIsReadNulSafely() {
        byte[] listing = ("100644 blob 1111111111111111111111111111111111111111      12\ta/__screenshots__/s.ts/t\tab.png\0"
                + "100644 blob 2222222222222222222222222222222222222222 1234567\tline\nbreak.png\0"
                + "160000 commit 3333333333333333333333333333333333333333       -\tsub\0"
                + "120000 blob 4444444444444444444444444444444444444444      10\tlink.png\0"
                + "100755 blob 5555555555555555555555555555555555555555       0\tcafé.png\0")
                .getBytes(StandardCharsets.UTF_8);

        Map<String, ScreenshotsReportKind.Blob> tree = ScreenshotsReportKind.tree(listing);

        assertThat(tree).containsExactly(
                Map.entry("a/__screenshots__/s.ts/t\tab.png", new ScreenshotsReportKind.Blob("1".repeat(40), 12)),
                Map.entry("line\nbreak.png", new ScreenshotsReportKind.Blob("2".repeat(40), 1234567)),
                Map.entry("café.png", new ScreenshotsReportKind.Blob("5".repeat(40), 0)));
    }

    @Test
    void theRendererRecordIsKeyValueAndItsValuesAreCapped() {
        assertThat(ScreenshotsReportKind.values("# provenance\n\nplaywright=1.50.0\r\nchromium = Chrome = 152 \nnokey\n=x\n"))
                .containsExactly(Map.entry("playwright", "1.50.0"), Map.entry("chromium", "Chrome = 152"));
        String capped = ScreenshotsReportKind.capped("v".repeat(500));
        assertThat(capped).hasSize(200).endsWith("…");
        assertThat(ScreenshotsReportKind.capped("short")).isEqualTo("short");
        assertThat(ScreenshotsReportKind.capped(null)).isNull();
    }

    @Test
    void highlightTextsFitAndALongKeyListIsCut() {
        String one = ScreenshotsReportKind.rendererChanged(List.of("chromium", "playwright"));
        assertThat(one).isEqualTo("renderer changed (chromium, playwright): diffs may be renderer noise");

        String many = ScreenshotsReportKind.rendererChanged(List.of("chromium", "chromium-headless-shell", "playwright",
                "fontconfig", "fonts-noto-core", "libfreetype6", "libharfbuzz0b"));
        assertThat(many).hasSizeLessThanOrEqualTo(Highlight.MAX_TEXT)
                .startsWith("renderer changed (chromium, ")
                .endsWith(", …): diffs may be renderer noise");
        String key = ScreenshotsReportKind.rendererChanged(List.of("k".repeat(90)));
        assertThat(key).isEqualTo("renderer changed (…): diffs may be renderer noise");

        ScreenshotsReport report = new ScreenshotsReport(null, "f", new ScreenshotsReport.BaselineSide(BASELINE, "c"),
                List.of(), new ScreenshotsReport.Totals(258, 4, 2, 1, 252), null, List.of(), false);
        assertThat(kind().highlight(report, Optional.empty(), null)).singleElement().satisfies(h -> {
            assertThat(h.text()).isEqualTo("4 new, 2 changed, 1 removed screenshots").hasSizeLessThanOrEqualTo(80);
            assertThat(h.value()).isEqualTo(7.0);
        });
        ScreenshotsReport partly = new ScreenshotsReport(null, "f", new ScreenshotsReport.BaselineSide(BASELINE, "c"),
                List.of(), new ScreenshotsReport.Totals(258, 0, 102, 0, 156), null, List.of(), false);
        assertThat(kind().highlight(partly, Optional.empty(), null)).extracting(Highlight::text)
                .containsExactly("102 changed screenshots");
        assertThat(kind().describe(partly)).isEqualTo("0 new, 102 changed, 0 removed");
    }

    @Test
    void thePayloadWritesNewAndReadsBack() throws Exception {
        ScreenshotsReport report = new ScreenshotsReport(null, "f", null,
                List.of(new ScreenshotsReport.Convention("vitest-browser", "vitest")),
                new ScreenshotsReport.Totals(1, 1, 0, 0, 0), null,
                List.of(new ScreenshotsReport.Entry("NEW", A, "vitest-browser", "s", "n", null, null, null, "b", null,
                        3L, null, null)), false);

        JsonNode json = ReportJson.MAPPER.valueToTree(report);

        assertThat(json.fieldNames()).toIterable().containsExactly("repositoryId", "headSha", "baseline",
                "conventions", "totals", "renderer", "entries", "truncated");
        assertThat(json.path("totals").fieldNames()).toIterable()
                .containsExactly("screenshots", "new", "changed", "removed", "unchanged");
        assertThat(json.path("entries").get(0).has("movedFrom")).as("nulls are written").isTrue();
        assertThat(ReportJson.MAPPER.treeToValue(json, ScreenshotsReport.class)).isEqualTo(report);
    }

    @Test
    void theKindIsRegisteredWithoutAParser() {
        ReportKinds kinds = ReportKinds.standard(warnings::add);

        assertThat(kinds.kinds()).extracting(ReportKind::id).endsWith("screenshots");
        assertThat(kinds.parsersOf("screenshots")).isEmpty();
        assertThat(kind().version()).isEqualTo(1);
        assertThat(kind().payloadType()).isEqualTo(ScreenshotsReport.class);
    }

    @Test
    void aBlankRepositoryIdIsNull() throws Exception {
        Path origin = init("blank");
        write(origin, A, "image");
        commit(origin, "fold");
        renderedHere(origin);
        ScreenshotsReportKind kind = new ScreenshotsReportKind(warnings::add,
                name -> name.equals("QITS_CI_REPO_ID") ? "  " : null, List.of(vitest));

        assertThat(kind.collect(step(origin, Optional.empty(), null), List.of()).orElseThrow().repositoryId()).isNull();
    }

    // --- the repositories ------------------------------------------------------------------------

    private ScreenshotsReportKind kind() {
        return new ScreenshotsReportKind(warnings::add, name -> name.equals("QITS_CI_REPO_ID") ? REPO_ID : null,
                List.of(vitest));
    }

    private static Baseline baseline() {
        return new Baseline(BASELINE, "run-0", "rr-0", null);
    }

    private StepContext step(Path root, Optional<Baseline> baseline, String url) {
        BaselineTag tag = baseline.isPresent() ? BaselineTag.in(root, baseline, url, warnings::add) : BaselineTag.none();
        return new StepContext(root, "run-1", 1, REPOSITORY, revQuietly(root), 0, baseline,
                ChangedLines.unavailable(), TestCaseLocators.registered(), tag);
    }

    /**
     * Tagged: A, B, C, E and a renderer record. The fold: B changed, C removed, D added, A moved to A',
     * E unchanged, the record's chromium line changed and a key added.
     */
    private Path origin() throws Exception {
        Path origin = init("origin");
        write(origin, A, "AAAAA");
        write(origin, B, "BBBBB");
        write(origin, C, "CCCCC");
        write(origin, E, "EEEEE");
        write(origin, RENDERER, "# copied from /etc/qits-renderer-provenance\nplaywright=1.50.0\n"
                + "chromium=Google Chrome for Testing 152.0\nfontconfig=2.15.0\n");
        write(origin, "README.md", "one\n");
        commit(origin, "baseline");
        must(origin, "tag", "-a", "-m", "the release", BASELINE);
        Files.createDirectories(origin.resolve(A_MOVED).getParent());
        Files.move(origin.resolve(A), origin.resolve(A_MOVED));
        write(origin, B, "BBBBBBBB");
        Files.delete(origin.resolve(C));
        write(origin, D, "DDDDDD");
        write(origin, RENDERER, "# copied from /etc/qits-renderer-provenance\nplaywright=1.50.0\n"
                + "chromium=Google Chrome for Testing 153.0\nfontconfig=2.15.0\nlibharfbuzz0b=8.3.0\n");
        commit(origin, "fold");
        must(origin, "branch", "fold");
        return origin;
    }

    private Path init(String name) throws Exception {
        Path repo = Files.createDirectories(work.resolve(name));
        must(repo, "init", "--quiet", "--initial-branch=main");
        return repo;
    }

    /** As qits-ci-daemon prepares a step: shallow, at the fold, without the tags; rendered here. */
    private Path shallowClone(Path origin) throws Exception {
        Path step = work.resolve("step");
        must(work, "clone", "--quiet", "--depth=1", "--no-tags", "--branch", "fold", url(origin), step.toString());
        renderedHere(step);
        return step;
    }

    private static void renderedHere(Path root) throws IOException {
        write(root, VitestBrowserScreenshots.RUN_RECORD, "{}");
    }

    private static String url(Path origin) {
        return origin.toUri().toString();
    }

    private static String rev(Path repo, String rev) throws Exception {
        Git.Result result = git(repo, "rev-parse", rev);
        assertThat(result.exit()).as(result.err()).isZero();
        return result.text().strip();
    }

    private static String revQuietly(Path repo) {
        try {
            return git(repo, "rev-parse", "HEAD").text().strip();
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Path repo, String path, String text) throws IOException {
        Path file = repo.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static void commit(Path repo, String message) throws Exception {
        // the run record is the step's, never committed
        must(repo, "add", "-A", "--", ".", ":!node_modules");
        must(repo, "commit", "--quiet", "-m", message);
    }

    private static void must(Path directory, String... arguments) throws Exception {
        Git.Result result = git(directory, arguments);
        assertThat(result.exit()).as("git %s: %s", arguments[0], result.err()).isZero();
    }

    static Git.Result git(Path directory, String... arguments) throws Exception {
        String[] all = new String[arguments.length + 4];
        System.arraycopy(new String[] {"-c", "user.name=qits", "-c", "user.email=qits@example.invalid"}, 0, all, 0, 4);
        System.arraycopy(arguments, 0, all, 4, arguments.length);
        return new Git(directory).run(all);
    }
}
