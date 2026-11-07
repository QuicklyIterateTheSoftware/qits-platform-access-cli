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
 * {@code stale-outputs}, version 1 (qits-1920): the {@code check-outputs:} kinds whose generated files
 * the QA step found stale, so the release request can name them and their regenerate command instead
 * of leaving them in the step log.
 *
 * <p>Fed by no parser: its input is the one file qits-ci's composed output check writes, named by
 * {@value #FILE}, one line per stale kind, {@code <kind>\t<regenerate command>}. Without the variable,
 * the file, or a non-blank line in it, nothing is reported: an older composer is harmless. A line
 * without a tab is skipped with a warning. No baseline comparison: a stale output is about this fold.
 */
public final class StaleOutputsReportKind implements ReportKind<StaleOutputs> {

    public static final String ID = "stale-outputs";
    public static final int VERSION = 1;

    /** The step's variable naming the composer's file; a relative path is under the step's root. */
    public static final String FILE = "QITS_CI_STALE_OUTPUTS";

    static final String NOT_REPORTED = "no stale outputs";

    private final Consumer<String> warnings;
    private final Function<String, String> env;

    /**
     * @param warnings where a line without a tab says so; one line each
     * @param env      the step's environment, for {@value #FILE}
     */
    public StaleOutputsReportKind(Consumer<String> warnings, Function<String, String> env) {
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
    public Class<StaleOutputs> payloadType() {
        return StaleOutputs.class;
    }

    @Override
    public String notReported() {
        return NOT_REPORTED;
    }

    @Override
    public Optional<StaleOutputs> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException {
        String named = env.apply(FILE);
        if (named == null || named.isBlank()) {
            return Optional.empty();
        }
        Path file = step.root().resolve(named.strip());
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        List<StaleOutputs.Kind> kinds = new ArrayList<>();
        int number = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            number++;
            if (line.isBlank()) {
                continue;
            }
            int tab = line.indexOf('\t');
            if (tab < 0) {
                warnings.accept(ID + ": skipped line " + number + " of " + named.strip() + ": no tab");
                continue;
            }
            kinds.add(new StaleOutputs.Kind(line.substring(0, tab).strip(), line.substring(tab + 1).strip()));
        }
        return kinds.isEmpty() ? Optional.empty() : Optional.of(new StaleOutputs(kinds));
    }

    @Override
    public List<Highlight> highlight(StaleOutputs report, Optional<StaleOutputs> baseline, StepContext step) {
        return report.kinds().stream().limit(Highlight.MAX_PER_REPORT)
                .map(k -> new Highlight(Highlight.BAD,
                        "stale outputs: " + k.kind() + " — regenerate with: " + k.regenerate(), null, null, null))
                .toList();
    }

    @Override
    public String describe(StaleOutputs report) {
        return report.kinds().size() + " stale: "
                + report.kinds().stream().map(StaleOutputs.Kind::kind).collect(Collectors.joining(", "));
    }
}
