package eu.wohlben.qits.cli.access.report;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
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
        return new ReportKinds(
                List.of(new TestResultsKind(warnings)),
                List.of(new SurefireXmlParser(), new FailsafeXmlParser(), new VitestJunitParser()));
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
