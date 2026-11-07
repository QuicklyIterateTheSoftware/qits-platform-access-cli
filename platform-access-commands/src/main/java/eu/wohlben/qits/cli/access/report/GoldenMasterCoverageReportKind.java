package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@code golden-master-coverage}, version 1 (qits-1940): the operations of a java service's OpenAPI
 * document that no golden master records, so the release request can name them.
 *
 * <p>Fed by no parser: its input is the file {@code qits contracts coverage --out} writes, named by
 * {@value #FILE}, one line per uncovered operation, {@code <name>\t<METHOD>\t<path>}. Without the
 * variable or the file nothing is reported: an older composer is harmless, and the command writes no
 * file when it could not read its inputs. Unlike {@code stale-outputs}, a file with no line in it IS
 * reported, with no operation uncovered: the command writes the file on every run it completes, so an
 * empty one is a clean pass, and that is worth recording. A line without three tab-separated fields is
 * skipped with a warning. No baseline comparison: coverage is about this fold.
 *
 * <p>The highlights are what qits-projects reads (it sees only {@code GET /ci/api/runs/{runId}/reports},
 * highlights without payloads), so their texts are fixed: first {@code uncovered operations: <n>}
 * ({@code bad}, or {@code good} at 0; metric {@value #METRIC}), then {@code uncovered: <name>} ({@code
 * bad}) for each of the first {@value #NAMED} operations in file order, which keeps a report within
 * {@link Highlight#MAX_PER_REPORT}. A name longer than {@link Highlight#MAX_TEXT} allows is cut there;
 * the payload's {@code uncovered} carries every operation whole.
 */
public final class GoldenMasterCoverageReportKind implements ReportKind<GoldenMasterCoverage> {

    public static final String ID = "golden-master-coverage";
    public static final int VERSION = 1;

    /** The step's variable naming the command's file; a relative path is under the step's root. */
    public static final String FILE = "QITS_CI_UNCOVERED_OPERATIONS";

    static final String NOT_REPORTED = "no coverage check in this step";
    static final String METRIC = "operations.uncovered";
    /** How many operations are named one highlight each, after the count's. */
    static final int NAMED = Highlight.MAX_PER_REPORT - 1;

    private final Consumer<String> warnings;
    private final Function<String, String> env;

    /**
     * @param warnings where a line that is not three fields says so; one line each
     * @param env      the step's environment, for {@value #FILE}
     */
    public GoldenMasterCoverageReportKind(Consumer<String> warnings, Function<String, String> env) {
        this.warnings = warnings;
        this.env = env;
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
    public Class<GoldenMasterCoverage> payloadType() {
        return GoldenMasterCoverage.class;
    }

    @Override
    public String notReported() {
        return NOT_REPORTED;
    }

    @Override
    public Optional<GoldenMasterCoverage> collect(StepContext step, List<ReportParser<?>> parsers)
            throws IOException {
        String named = env.apply(FILE);
        if (named == null || named.isBlank()) {
            return Optional.empty();
        }
        Path file = step.root().resolve(named.strip());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<GoldenMasterCoverage.Operation> uncovered = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || fields[0].isBlank()) {
                warnings.accept(ID + ": skipped line " + number + " of " + named.strip()
                        + ": not <name>\\t<method>\\t<path>");
                continue;
            }
            uncovered.add(new GoldenMasterCoverage.Operation(fields[0].strip(), fields[1].strip(), fields[2].strip()));
        }
        return Optional.of(new GoldenMasterCoverage(uncovered));
    }

    @Override
    public List<Highlight> highlight(GoldenMasterCoverage report, Optional<GoldenMasterCoverage> baseline,
                                     StepContext step) {
        int count = report.uncovered().size();
        List<Highlight> highlights = new ArrayList<>();
        highlights.add(new Highlight(count == 0 ? Highlight.GOOD : Highlight.BAD, "uncovered operations: " + count,
                METRIC, (double) count, null));
        report.uncovered().stream().limit(NAMED)
                .forEach(o -> highlights.add(new Highlight(Highlight.BAD, "uncovered: " + o.name(), null, null, null)));
        return highlights;
    }

    @Override
    public String describe(GoldenMasterCoverage report) {
        return report.uncovered().isEmpty() ? "0 uncovered" : report.uncovered().size() + " uncovered: "
                + report.uncovered().stream().map(GoldenMasterCoverage.Operation::name)
                .collect(Collectors.joining(", "));
    }
}
