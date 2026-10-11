package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * {@code coverage}, version 1: total line coverage with its delta against the baseline, and diff
 * coverage, the coverage of the lines the fold changed against the baseline's tag. Fed by JaCoCo's
 * exec file and istanbul's {@code coverage-final.json} ({@link ReportKinds}); a step that has both
 * (a reactor with a frontend in it) is merged by file.
 */
public final class CoverageKind implements ReportKind<Coverage> {

    public static final String ID = "coverage";
    public static final int VERSION = 1;

    /** Diff coverage from this percent up is {@code good}. */
    static final double GOOD_FROM = 80.0;
    /** Diff coverage below this percent is {@code warn}. Never {@code bad}: a report never fails a release. */
    static final double WARN_BELOW = 50.0;

    private final Consumer<String> warnings;

    public CoverageKind(Consumer<String> warnings) {
        this.warnings = warnings;
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
    public Class<Coverage> payloadType() {
        return Coverage.class;
    }

    @Override
    public Optional<Coverage> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        Set<Coverage.Source> sources = new LinkedHashSet<>();
        Map<String, NavigableMap<Integer, Boolean>> merged = new TreeMap<>();
        for (ReportParser<?> parser : parsers) {
            for (Path file : parser.discover(step.root())) {
                LineCoverage parsed;
                try {
                    if (!(parser.parse(file, step) instanceof LineCoverage coverage)) {
                        continue;
                    }
                    parsed = coverage;
                } catch (IOException | RuntimeException unreadable) {
                    warnings.accept(ID + ": skipped " + step.relative(file) + " (" + parser.tool() + "): "
                            + describe(unreadable));
                    continue;
                }
                sources.add(new Coverage.Source(parsed.language(), parsed.tool()));
                parsed.files().forEach((path, lines) -> {
                    NavigableMap<Integer, Boolean> into = merged.computeIfAbsent(path, p -> new TreeMap<>());
                    lines.forEach((line, covered) -> into.merge(line, covered, Boolean::logicalOr));
                });
            }
        }
        if (sources.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(summed(List.copyOf(sources), merged, step));
    }

    static Coverage summed(List<Coverage.Source> sources, Map<String, NavigableMap<Integer, Boolean>> lines,
                           StepContext step) {
        int covered = 0;
        int total = 0;
        List<Coverage.FileCoverage> files = new ArrayList<>();
        for (Map.Entry<String, NavigableMap<Integer, Boolean>> file : lines.entrySet()) {
            int fileTotal = file.getValue().size();
            if (fileTotal == 0) {
                continue;
            }
            int fileCovered = (int) file.getValue().values().stream().filter(Boolean::booleanValue).count();
            covered += fileCovered;
            total += fileTotal;
            files.add(new Coverage.FileCoverage(file.getKey(), fileCovered, fileTotal));
        }
        return new Coverage(sources, new Coverage.Total(covered, total, percent(covered, total)), null,
                diff(lines, step), files);
    }

    /** Null without a baseline, or when the changed lines could not be had. */
    private static Coverage.Diff diff(Map<String, NavigableMap<Integer, Boolean>> lines, StepContext step) {
        if (step.baseline().isEmpty() || !step.changedLines().available()) {
            return null;
        }
        int changed = 0;
        int covered = 0;
        List<Coverage.Uncovered> uncovered = new ArrayList<>();
        for (String file : new TreeSet<>(step.changedLines().files())) {
            NavigableMap<Integer, Boolean> coverable = lines.get(file);
            if (coverable == null) {
                continue;
            }
            List<Integer> missed = new ArrayList<>();
            for (int line : new TreeSet<>(step.changedLines().lines(file))) {
                Boolean hit = coverable.get(line);
                if (hit == null) {
                    continue;
                }
                changed++;
                if (hit) {
                    covered++;
                } else {
                    missed.add(line);
                }
            }
            if (!missed.isEmpty()) {
                uncovered.add(new Coverage.Uncovered(file, ranges(missed)));
            }
        }
        return new Coverage.Diff(step.baseline().get().version(), changed, covered, percent(covered, changed),
                uncovered);
    }

    /** Ascending line numbers as {@code [first, last]} runs of consecutive lines. */
    static List<List<Integer>> ranges(List<Integer> ascending) {
        List<List<Integer>> ranges = new ArrayList<>();
        int first = ascending.getFirst();
        int last = first;
        for (int line : ascending.subList(1, ascending.size())) {
            if (line == last + 1) {
                last = line;
            } else {
                ranges.add(List.of(first, last));
                first = line;
                last = line;
            }
        }
        ranges.add(List.of(first, last));
        return ranges;
    }

    /** {@code part} of {@code whole} in percent, to two places; null when there is nothing to measure. */
    static Double percent(int part, int whole) {
        if (whole == 0) {
            return null;
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Override
    public Coverage compared(Coverage report, Optional<Coverage> baseline, StepContext step) {
        if (partial(step) || baseline.isEmpty() || step.baseline().isEmpty() || baseline.get().total() == null
                || baseline.get().total().percent() == null) {
            return report.withBaselineTotal(null);
        }
        return report.withBaselineTotal(new Coverage.BaselineTotal(step.baseline().get().version(),
                baseline.get().total().percent()));
    }

    @Override
    public List<Highlight> highlight(Coverage report, Optional<Coverage> baseline, StepContext step) {
        List<Highlight> highlights = new ArrayList<>();
        Coverage.Diff diff = report.diff();
        if (diff != null) {
            if (diff.percent() == null) {
                highlights.add(new Highlight(Highlight.INFO, "diff coverage: no coverable line changed",
                        "coverage.diff", null, null));
            } else {
                String severity = partial(step) ? Highlight.INFO
                        : diff.percent() >= GOOD_FROM ? Highlight.GOOD
                        : diff.percent() < WARN_BELOW ? Highlight.WARN : Highlight.INFO;
                highlights.add(new Highlight(severity, "diff coverage " + format(diff.percent()) + "% ("
                        + diff.linesCovered() + "/" + diff.linesChanged() + " changed lines"
                        + (partial(step) ? ", partial run" : "") + ")",
                        "coverage.diff", diff.percent(), null));
            }
        }
        Double total = report.total().percent();
        if (total != null) {
            Coverage.BaselineTotal before = report.baselineTotal();
            if (partial(step)) {
                highlights.add(new Highlight(Highlight.INFO, "coverage " + format(total) + "% (partial: step exited "
                        + step.exitCode() + ", not compared)", "coverage.total", total, null));
            } else if (before != null && before.percent() != null) {
                double delta = BigDecimal.valueOf(total - before.percent()).setScale(2, RoundingMode.HALF_UP)
                        .doubleValue();
                highlights.add(new Highlight(Highlight.INFO, "coverage " + format(total) + "% (" + signed(delta) + ")",
                        "coverage.total", total, delta));
            } else {
                highlights.add(new Highlight(Highlight.INFO, "coverage " + format(total) + "% (no baseline)",
                        "coverage.total", total, null));
            }
        } else {
            highlights.add(new Highlight(Highlight.INFO, "coverage: no coverable line", "coverage.total", null,
                    null));
        }
        return highlights;
    }

    /**
     * A step whose script exited non-zero may have stopped part way (a failed module ends the reactor),
     * so its numbers cover only what ran. They are reported, and never compared with the baseline,
     * which a released, so complete, run produced (qits-1171).
     */
    static boolean partial(StepContext step) {
        return step.exitCode() != 0;
    }

    @Override
    public String describe(Coverage report) {
        Coverage.Total total = report.total();
        return total.linesTotal() + " lines, " + (total.percent() == null ? "none coverable"
                : format(total.percent()) + "% covered");
    }

    private static String format(double percent) {
        return String.format(Locale.ROOT, "%.1f", percent);
    }

    private static String signed(double delta) {
        if (format(Math.abs(delta)).equals("0.0")) {
            return "±0.0";
        }
        return (delta > 0 ? "+" : "") + format(delta);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName()
                : message.replaceAll("\\s+", " ").strip();
    }
}
