package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One line a reviewer sees next to the verdict: "3 tests failed", "coverage -2.1%". Never a verdict
 * itself: a report never fails or holds a release.
 *
 * @param severity {@code good}, {@code info}, {@code warn} or {@code bad}
 * @param text     at most {@value #MAX_TEXT} characters; longer is cut
 * @param metric   what {@code value} and {@code delta} measure ({@code tests.failed}); null when the
 *                 line carries no number
 * @param value    the number, or null
 * @param delta    its change against the baseline, or null
 */
@RegisterForReflection
public record Highlight(String severity, String text, String metric, Double value, Double delta) {

    public static final String GOOD = "good";
    public static final String INFO = "info";
    public static final String WARN = "warn";
    public static final String BAD = "bad";

    public static final int MAX_TEXT = 80;

    /** At most this many per report; a kind's further lines are dropped. */
    public static final int MAX_PER_REPORT = 10;

    public Highlight {
        if (!GOOD.equals(severity) && !INFO.equals(severity) && !WARN.equals(severity) && !BAD.equals(severity)) {
            throw new IllegalArgumentException("not a highlight severity: " + severity);
        }
        text = text == null ? "" : text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
