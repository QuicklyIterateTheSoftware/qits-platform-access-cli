package eu.wohlben.qits.cli.access.report;

import java.util.List;

/**
 * What one test-result file says: its counts and its failing test cases, uncapped. The
 * {@code test-results} kind adds the files up; this record never leaves the CLI.
 */
public record TestFile(String language, String tool, String module, int tests, int failed, int errored,
                       int skipped, long durationMs, List<TestResults.Failure> failures) {
}
