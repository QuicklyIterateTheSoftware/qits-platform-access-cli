package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * The {@code test-results} payload, version 1: the totals, one line per language, tool and module,
 * and each failing test case. Passing tests are only counted, which keeps a large repository's
 * payload small. Every record here is written and read back by Jackson, hence the registrations.
 *
 * @param truncated more than {@value TestResultsKind#MAX_FAILURES} test cases failed, and only the
 *                  first are listed
 */
@RegisterForReflection
public record TestResults(Totals totals, List<Suite> suites, List<Failure> failures, boolean truncated) {

    /** Every test case of the step. {@code passed} is what is left after the other three. */
    @RegisterForReflection
    public record Totals(int tests, int passed, int failed, int errored, int skipped, long durationMs) {
    }

    /** The test cases of one tool in one module. {@code module} is relative to the root; "." is the root. */
    @RegisterForReflection
    public record Suite(String language, String tool, String module, int tests, int failed, int errored, int skipped,
                        long durationMs) {
    }

    /**
     * One failing test case.
     *
     * @param shape       {@code ASSERTION}, {@code ERROR}, {@code TIMEOUT} or {@code SETUP}
     * @param failureType the exception's class, as the tool named it; null when it did not
     * @param message     the exception's message, at most 4 KiB; null when there was none
     * @param stackTrace  at most 16 KiB; null when there was none
     */
    @RegisterForReflection
    public record Failure(TestCoordinates coordinates, String shape, String failureType, String message,
                          String stackTrace, Long durationMs) {

        Failure withCoordinates(TestCoordinates located) {
            return new Failure(located, shape, failureType, message, stackTrace, durationMs);
        }
    }

    /** A test case's outcome as a reviewer reads it. */
    public static final class Shape {

        /** A JUnit {@code <failure>}, or a vitest assertion error. */
        public static final String ASSERTION = "ASSERTION";
        /** A JUnit {@code <error>}, or any other error vitest reports. */
        public static final String ERROR = "ERROR";
        /** A timeout type or message. */
        public static final String TIMEOUT = "TIMEOUT";
        /** A class or suite that failed with no test of its own: initializationError, a spec that did not load. */
        public static final String SETUP = "SETUP";

        private Shape() {
        }
    }
}
