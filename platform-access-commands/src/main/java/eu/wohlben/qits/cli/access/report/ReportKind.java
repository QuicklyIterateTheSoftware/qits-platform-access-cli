package eu.wohlben.qits.cli.access.report;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * One kind of release report. The server and the UI never see this type: they see only its id, its
 * version and its JSON. A new kind is one implementation of this and one entry in
 * {@link ReportKinds}; nothing else changes, here or on the platform.
 *
 * @param <P> the payload, a record Jackson writes and reads back (it carries
 *            {@code @RegisterForReflection}, all the way down, for the native binary)
 */
public interface ReportKind<P> {

    /** The wire name, {@code [a-z][a-z0-9-]{0,63}}: "test-results", "coverage". */
    String id();

    /** The payload's schema version; bumped only on an incompatible change. */
    int version();

    /** To read the baseline's payload back. */
    Class<P> payloadType();

    /**
     * Finds and parses this kind's inputs in the step's tree. Empty means "not reported": nothing is
     * submitted. A file that does not parse is skipped with a warning; it never fails the collect.
     *
     * @param parsers the registered parsers that feed this kind ({@link ReportParser#kind()} is
     *                {@link #id()})
     */
    Optional<P> collect(StepContext step, List<ReportParser<?>> parsers) throws IOException;

    /**
     * The report as it is submitted, now that the baseline's payload of this kind is known: for a kind
     * that keeps a comparison in its payload ({@code coverage}'s {@code baselineTotal}). Called once,
     * after {@link #collect} and before {@link #highlight}. Unchanged by default.
     */
    default P compared(P report, Optional<P> baseline, StepContext step) {
        return report;
    }

    /**
     * Summarises into at most 10 highlights, comparing with the baseline's payload of this kind when
     * one exists. A highlight is never a verdict: a report never fails or holds a release.
     */
    List<Highlight> highlight(P report, Optional<P> baseline, StepContext step);

    /**
     * What a submit says after "submitted", in parentheses: {@code 412 tests, 3 failed}. Empty says
     * nothing more.
     */
    default String describe(P report) {
        return "";
    }

    /**
     * Why the last {@link #collect} answered empty, in the parentheses after "not reported":
     * {@code no inputs} by default; a kind that can say more ({@code not rendered in this step}) says it.
     */
    default String notReported() {
        return "no inputs";
    }
}
