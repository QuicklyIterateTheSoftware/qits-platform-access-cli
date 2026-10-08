package eu.wohlben.qits.cli.access.report;

import eu.wohlben.qits.cli.access.report.contracts.ContractsReportKind;
import eu.wohlben.qits.cli.access.report.contracts.GoldenMasterIndexParser;
import eu.wohlben.qits.cli.access.report.contracts.PactFileParser;
import eu.wohlben.qits.cli.access.report.contracts.PactJvmVerificationReportParser;
import eu.wohlben.qits.cli.access.report.screenshots.ScreenshotConventions;
import eu.wohlben.qits.cli.access.report.screenshots.ScreenshotsReportKind;
import eu.wohlben.qits.cli.access.report.screenshots.VitestBrowserScreenshots;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The one registry: every report kind this CLI produces, and every parser that feeds one. A new kind
 * is one more entry in {@link #standard}'s first list, plus its class; a new language or tool for an
 * existing kind is one more parser in the second. {@code qits ci report submit} walks what is here and
 * knows nothing else.
 */
public final class ReportKinds {

    /** A kind's wire name, as qits-ci accepts it in a path. */
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    private final List<ReportKind<?>> kinds;
    private final List<ReportParser<?>> parsers;

    public ReportKinds(List<ReportKind<?>> kinds, List<ReportParser<?>> parsers) {
        Set<String> ids = new HashSet<>();
        for (ReportKind<?> kind : kinds) {
            if (!ID.matcher(kind.id()).matches()) {
                throw new IllegalArgumentException("not a report kind name: " + kind.id());
            }
            if (!ids.add(kind.id())) {
                throw new IllegalArgumentException("report kind registered twice: " + kind.id());
            }
        }
        for (ReportParser<?> parser : parsers) {
            if (!ids.contains(parser.kind())) {
                throw new IllegalArgumentException(parser.getClass().getSimpleName() + " feeds " + parser.kind()
                        + ", which is not registered");
            }
        }
        this.kinds = List.copyOf(kinds);
        this.parsers = List.copyOf(parsers);
    }

    /**
     * What this release of the CLI reports.
     *
     * @param warnings where a kind says it skipped something (a file that does not parse); one line each
     */
    public static ReportKinds standard(Consumer<String> warnings) {
        return standard(warnings, System::getenv, VitestBrowserScreenshots.PROVENANCE);
    }

    /**
     * What this release of the CLI reports, in a step whose environment is {@code env}.
     *
     * @param warnings           where a kind says it skipped something; one line each
     * @param env                the step's environment, for what {@link StepContext} does not carry
     *                           ({@code screenshots} reads {@code QITS_CI_REPO_ID})
     * @param rendererProvenance the renderer image's provenance record; its presence says the step
     *                           rendered screenshots
     */
    public static ReportKinds standard(Consumer<String> warnings, Function<String, String> env,
                                       Path rendererProvenance) {
        List<ReportParser<?>> parsers = List.of(new SurefireXmlParser(), new FailsafeXmlParser(),
                new VitestJunitParser(), new JacocoExecParser(), new IstanbulJsonParser(), new PactFileParser(),
                new PactJvmVerificationReportParser(), new GoldenMasterIndexParser());
        // contracts are reported only in a step that ran tests, whichever parsers find those
        List<ReportParser<?>> testResults = parsers.stream()
                .filter(p -> p.kind().equals(TestResultsKind.ID)).toList();
        return new ReportKinds(
                List.of(new TestResultsKind(warnings), new CoverageKind(warnings),
                        new ContractsReportKind(warnings, testResults), new EntityChangesReportKind(warnings),
                        new ScreenshotsReportKind(warnings, env, ScreenshotConventions.standard(rendererProvenance))),
                parsers);
    }

    public List<ReportKind<?>> kinds() {
        return kinds;
    }

    public List<ReportParser<?>> parsers() {
        return parsers;
    }

    /** The parsers that feed {@code kind}, in registry order. */
    public List<ReportParser<?>> parsersOf(String kind) {
        return parsers.stream().filter(p -> p.kind().equals(kind)).toList();
    }
}
