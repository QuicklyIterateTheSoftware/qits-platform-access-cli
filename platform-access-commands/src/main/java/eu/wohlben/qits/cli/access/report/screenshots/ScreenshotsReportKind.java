package eu.wohlben.qits.cli.access.report.screenshots;

import eu.wohlben.qits.cli.access.report.Baseline;
import eu.wohlben.qits.cli.access.report.BaselineTag;
import eu.wohlben.qits.cli.access.report.Git;
import eu.wohlben.qits.cli.access.report.Highlight;
import eu.wohlben.qits.cli.access.report.ReportKind;
import eu.wohlben.qits.cli.access.report.ReportParser;
import eu.wohlben.qits.cli.access.report.StepContext;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * {@code screenshots}, version 1 (epic qits-762, Design §1): the committed screenshot baselines the
 * fold adds, changes and removes against the baseline's tag, so the release request is where a
 * reviewer accepts a visual change. Read from git as two path → blob maps ({@code git ls-tree -r -l
 * -z}), never rendered here and never sent as bytes.
 *
 * <p>Fed by no parser: its input is the committed tree. Reported only in a step where some
 * {@link ScreenshotConvention} says it rendered, so a run reports its screenshots once and not once per
 * QA step; and only when either side holds a baseline image.
 *
 * <p>Never fails on the baseline: without one, or when its tag cannot be had (said once by
 * {@link BaselineTag}) or read (said once here), every image at the fold is NEW and {@code baseline}
 * and {@code renderer} are null. A first release really is all new.
 */
public final class ScreenshotsReportKind implements ReportKind<ScreenshotsReport> {

    public static final String ID = "screenshots";
    public static final int VERSION = 1;

    /** The githost storage id the step's event announced; {@link StepContext} does not carry it. */
    public static final String REPO_ID = "QITS_CI_REPO_ID";

    /** Entries listed; the totals count every one. 1000 entries are about 400 KB. */
    public static final int MAX_ENTRIES = 1000;

    /** A renderer record's value, in characters, as the payload carries it. */
    public static final int MAX_RENDERER_VALUE = 200;

    static final String NOT_RENDERED = "not rendered in this step";
    static final String NO_BASELINES = "no screenshot baselines";

    private static final String RENDERER_PREFIX = "renderer changed (";
    private static final String RENDERER_SUFFIX = "): diffs may be renderer noise";

    private final Consumer<String> warnings;
    private final Function<String, String> env;
    private final List<ScreenshotConvention> conventions;

    private volatile String notReported = "no inputs";

    /**
     * @param warnings    where a baseline that cannot be read says so; one line
     * @param env         the step's environment, for {@value #REPO_ID}
     * @param conventions {@link ScreenshotConventions#standard()}, unless a test says otherwise
     */
    public ScreenshotsReportKind(Consumer<String> warnings, Function<String, String> env,
                                 List<ScreenshotConvention> conventions) {
        this.warnings = warnings;
        this.env = env;
        this.conventions = List.copyOf(conventions);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Class<ScreenshotsReport> payloadType() {
        return ScreenshotsReport.class;
    }

    @Override
    public String notReported() {
        return notReported;
    }

    /** A blob in a tree: its object id and its size in bytes. */
    record Blob(String sha, long size) {
    }

    /** A baseline image on one side. */
    private record Shot(String path, ScreenshotConvention convention, ScreenshotId id, Blob blob) {
    }

    /** The baseline's side: its version, its commit, and its whole tree. */
    private record Side(String version, String commitSha, Map<String, Blob> tree) {
    }

    @Override
    public Optional<ScreenshotsReport> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        List<ScreenshotConvention> rendering = conventions.stream().filter(c -> c.renderedHere(step.root())).toList();
        if (rendering.isEmpty()) {
            notReported = NOT_RENDERED;
            return Optional.empty();
        }
        Git git = new Git(step.root());
        Map<String, Blob> foldTree;
        Optional<Side> base;
        try {
            Git.Result listed = git.run("ls-tree", "-r", "-l", "-z", "--full-name", "HEAD");
            if (!listed.ok()) {
                throw new IOException("`git ls-tree HEAD` failed: " + Git.said(listed));
            }
            foldTree = tree(listed.out());
            base = baseline(step, git);
        } catch (Git.Failure failed) {
            throw new IOException(failed.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while reading git");
        }
        Map<String, Shot> after = shots(foldTree, rendering);
        Map<String, Shot> before = base.map(side -> shots(side.tree(), rendering)).orElse(Map.of());
        if (after.isEmpty() && before.isEmpty()) {
            notReported = NO_BASELINES;
            return Optional.empty();
        }

        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        int unchanged = 0;
        for (Map.Entry<String, Shot> shot : after.entrySet()) {
            Shot was = before.get(shot.getKey());
            if (was == null) {
                added.add(shot.getKey());
            } else if (!was.blob().sha().equals(shot.getValue().blob().sha())) {
                changed.add(shot.getKey());
            } else {
                unchanged++;
            }
        }
        for (String path : before.keySet()) {
            if (!after.containsKey(path)) {
                removed.add(path);
            }
        }

        // Exact moves: a NEW blob equal to a REMOVED one, paired one to one, both in path order.
        Map<String, Deque<String>> removedByBlob = new HashMap<>();
        for (String path : removed) {
            removedByBlob.computeIfAbsent(before.get(path).blob().sha(), sha -> new ArrayDeque<>()).add(path);
        }
        Map<String, String> movedFrom = new HashMap<>();
        Map<String, String> movedTo = new HashMap<>();
        for (String path : added) {
            Deque<String> candidates = removedByBlob.get(after.get(path).blob().sha());
            if (candidates != null && !candidates.isEmpty()) {
                String from = candidates.poll();
                movedFrom.put(path, from);
                movedTo.put(from, path);
            }
        }

        List<ScreenshotsReport.Entry> entries = new ArrayList<>();
        for (String path : added) {
            entries.add(entry(ScreenshotsReport.NEW, null, after.get(path), movedFrom.get(path), null));
        }
        for (String path : changed) {
            entries.add(entry(ScreenshotsReport.CHANGED, before.get(path), after.get(path), null, null));
        }
        for (String path : removed) {
            entries.add(entry(ScreenshotsReport.REMOVED, before.get(path), null, null, movedTo.get(path)));
        }
        boolean truncated = entries.size() > MAX_ENTRIES;
        if (truncated) {
            entries = entries.subList(0, MAX_ENTRIES);
        }

        ScreenshotsReport.Renderer renderer = base.isEmpty() ? null : renderer(rendering, foldTree, base.get(), git);
        String repositoryId = env.apply(REPO_ID);
        return Optional.of(new ScreenshotsReport(
                repositoryId == null || repositoryId.isBlank() ? null : repositoryId.strip(),
                step.commitSha(),
                base.map(side -> new ScreenshotsReport.BaselineSide(side.version(), side.commitSha())).orElse(null),
                rendering.stream().map(c -> new ScreenshotsReport.Convention(c.id(), c.tool())).toList(),
                new ScreenshotsReport.Totals(after.size(), added.size(), changed.size(), removed.size(), unchanged),
                renderer, entries, truncated));
    }

    private static ScreenshotsReport.Entry entry(String status, Shot was, Shot is, String movedFrom, String movedTo) {
        Shot shot = is != null ? is : was;
        return new ScreenshotsReport.Entry(status, shot.path(), shot.convention().id(), shot.id().spec(),
                shot.id().name(), shot.id().browser(), shot.id().platform(),
                was == null ? null : was.blob().sha(), is == null ? null : is.blob().sha(),
                was == null ? null : was.blob().size(), is == null ? null : is.blob().size(),
                movedFrom, movedTo);
    }

    // --- the two sides ---------------------------------------------------------------------------

    /**
     * The baseline's tag peeled to its commit, and its tree; empty without a baseline, or when the tag
     * cannot be had (it said why) or read (said here, once).
     */
    private Optional<Side> baseline(StepContext step, Git git) throws InterruptedException {
        Optional<Baseline> baseline = step.baseline();
        if (baseline.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> ref = step.baselineTag().ref();
        if (ref.isEmpty()) {
            return Optional.empty();
        }
        String version = baseline.get().version();
        try {
            Git.Result peeled = git.run("rev-parse", "--verify", "--quiet", ref.get() + "^{commit}");
            if (!peeled.ok()) {
                warnings.accept(ID + ": the baseline tag " + version + " names no commit, so no baseline");
                return Optional.empty();
            }
            String commit = peeled.text().strip();
            Git.Result listed = git.run("ls-tree", "-r", "-l", "-z", "--full-name", commit);
            if (!listed.ok()) {
                warnings.accept(ID + ": the tree at " + version + " could not be read, so no baseline: "
                        + Git.said(listed));
                return Optional.empty();
            }
            return Optional.of(new Side(version, commit, tree(listed.out())));
        } catch (Git.Failure failed) {
            warnings.accept(ID + ": " + failed.getMessage() + ", so no baseline");
            return Optional.empty();
        }
    }

    /**
     * {@code git ls-tree -r -l -z} as path → blob. Each record is {@code <mode> SP <type> SP <object>
     * SP+ <size> TAB <path>}, ended by NUL, the path verbatim: nothing in a path can split a record.
     * Gitlinks and symlinks are not files and are left out.
     */
    static Map<String, Blob> tree(byte[] listing) {
        Map<String, Blob> blobs = new LinkedHashMap<>();
        for (String record : new String(listing, StandardCharsets.UTF_8).split("\0")) {
            int tab = record.indexOf('\t');
            if (tab < 0) {
                continue;
            }
            String[] head = record.substring(0, tab).strip().split(" +");
            if (head.length != 4 || !head[1].equals("blob") || head[0].equals("120000")) {
                continue;
            }
            long size;
            try {
                size = Long.parseLong(head[3]);
            } catch (NumberFormatException notASize) {
                continue;
            }
            blobs.put(record.substring(tab + 1), new Blob(head[2], size));
        }
        return blobs;
    }

    /** The blobs some rendering convention identifies, by path, sorted. */
    private static Map<String, Shot> shots(Map<String, Blob> tree, List<ScreenshotConvention> rendering) {
        Map<String, Shot> shots = new TreeMap<>();
        tree.forEach((path, blob) -> {
            for (ScreenshotConvention convention : rendering) {
                Optional<ScreenshotId> id = convention.identify(path);
                if (id.isPresent()) {
                    shots.put(path, new Shot(path, convention, id.get(), blob));
                    break;
                }
            }
        });
        return shots;
    }

    // --- the renderer ----------------------------------------------------------------------------

    /**
     * The first rendering convention's record that either side holds, compared key by key; null when
     * neither side holds one, or when a side cannot be read (said once).
     */
    private ScreenshotsReport.Renderer renderer(List<ScreenshotConvention> rendering, Map<String, Blob> foldTree,
                                                Side base, Git git) {
        for (ScreenshotConvention convention : rendering) {
            Optional<String> atFold = convention.rendererRecord(foldTree.keySet());
            Optional<String> atBase = convention.rendererRecord(base.tree().keySet());
            if (atFold.isEmpty() && atBase.isEmpty()) {
                continue;
            }
            String path = atFold.orElseGet(atBase::get);
            Blob after = atFold.map(foldTree::get).orElse(null);
            Blob before = atBase.map(base.tree()::get).orElse(null);
            if (after != null && before != null && after.sha().equals(before.sha())) {
                return new ScreenshotsReport.Renderer(path, false, List.of());
            }
            try {
                Map<String, String> was = before == null ? Map.of() : values(read(git, before.sha()));
                Map<String, String> is = after == null ? Map.of() : values(read(git, after.sha()));
                List<ScreenshotsReport.RendererEntry> entries = new ArrayList<>();
                TreeSet<String> keys = new TreeSet<>(was.keySet());
                keys.addAll(is.keySet());
                for (String key : keys) {
                    if (!Objects.equals(was.get(key), is.get(key))) {
                        entries.add(new ScreenshotsReport.RendererEntry(key, capped(was.get(key)),
                                capped(is.get(key))));
                    }
                }
                return new ScreenshotsReport.Renderer(path, !entries.isEmpty(), entries);
            } catch (Git.Failure | IOException unreadable) {
                warnings.accept(ID + ": the renderer record " + path + " could not be read, so no renderer "
                        + "comparison: " + unreadable.getMessage());
                return null;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    private static String read(Git git, String sha) throws Git.Failure, InterruptedException, IOException {
        Git.Result blob = git.run("cat-file", "blob", sha);
        if (!blob.ok()) {
            throw new IOException(Git.said(blob));
        }
        return blob.text();
    }

    /** {@code key=value} lines; blank lines, {@code #} comments and lines without a key are skipped. */
    static Map<String, String> values(String record) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : record.split("\\R")) {
            String text = line.strip();
            int equals = text.indexOf('=');
            if (text.isEmpty() || text.startsWith("#") || equals <= 0) {
                continue;
            }
            values.put(text.substring(0, equals).strip(), text.substring(equals + 1).strip());
        }
        return values;
    }

    /** At most {@value #MAX_RENDERER_VALUE} characters, ending in "…" when cut; null stays null. */
    static String capped(String value) {
        if (value == null || value.length() <= MAX_RENDERER_VALUE) {
            return value;
        }
        int end = MAX_RENDERER_VALUE - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    // --- what a reviewer reads -------------------------------------------------------------------

    /**
     * At most two lines: the changes (or "unchanged", or "no baseline"), and a warning when the renderer
     * changed. {@code baseline} (the previous report) is ignored: a git-file kind compares with the tag.
     */
    @Override
    public List<Highlight> highlight(ScreenshotsReport report, Optional<ScreenshotsReport> baseline,
                                     StepContext step) {
        List<Highlight> highlights = new ArrayList<>();
        ScreenshotsReport.Totals totals = report.totals();
        if (report.baseline() == null) {
            highlights.add(new Highlight(Highlight.INFO, totals.screenshots() + " screenshots, no baseline to compare",
                    null, null, null));
        } else {
            int changes = totals.added() + totals.changed() + totals.removed();
            if (changes == 0) {
                highlights.add(new Highlight(Highlight.GOOD, "screenshots unchanged (" + totals.screenshots() + ")",
                        null, null, null));
            } else {
                List<String> parts = new ArrayList<>();
                if (totals.added() > 0) {
                    parts.add(totals.added() + " new");
                }
                if (totals.changed() > 0) {
                    parts.add(totals.changed() + " changed");
                }
                if (totals.removed() > 0) {
                    parts.add(totals.removed() + " removed");
                }
                highlights.add(new Highlight(Highlight.INFO, String.join(", ", parts) + " screenshots",
                        "screenshots.changes", (double) changes, null));
            }
        }
        if (report.renderer() != null && report.renderer().changed()) {
            highlights.add(new Highlight(Highlight.WARN, rendererChanged(
                    report.renderer().entries().stream().map(ScreenshotsReport.RendererEntry::key).toList()),
                    null, null, null));
        }
        return highlights;
    }

    /**
     * "renderer changed (chromium, playwright): diffs may be renderer noise", in at most
     * {@value Highlight#MAX_TEXT} characters: the key list is cut, ending in "…", rather than the line.
     */
    static String rendererChanged(List<String> keys) {
        int budget = Highlight.MAX_TEXT - RENDERER_PREFIX.length() - RENDERER_SUFFIX.length();
        String all = String.join(", ", keys);
        if (all.length() <= budget) {
            return RENDERER_PREFIX + all + RENDERER_SUFFIX;
        }
        StringBuilder list = new StringBuilder();
        for (String key : keys) {
            String piece = (list.isEmpty() ? "" : ", ") + key;
            if (list.length() + piece.length() + ", …".length() > budget) {
                break;
            }
            list.append(piece);
        }
        list.append(list.isEmpty() ? "…" : ", …");
        return RENDERER_PREFIX + list + RENDERER_SUFFIX;
    }

    @Override
    public String describe(ScreenshotsReport report) {
        ScreenshotsReport.Totals totals = report.totals();
        return totals.added() + " new, " + totals.changed() + " changed, " + totals.removed() + " removed";
    }
}
