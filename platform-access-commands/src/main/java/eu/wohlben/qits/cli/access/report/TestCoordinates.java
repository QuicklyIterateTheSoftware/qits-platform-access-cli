package eu.wohlben.qits.cli.access.report;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Which test, exactly: what a failure names, and what a locator and a code preview start from.
 *
 * @param language   "java", "typescript"
 * @param tool       "surefire", "failsafe", "vitest"
 * @param repository the repository the step built
 * @param commitSha  the fold's commit ({@code QITS_CI_SHA}), the one the file is read at
 * @param file       relative to the repository's root; null when the parser could not resolve it
 * @param className  the class for Java; the {@code describe} path joined with {@code " > "} for vitest
 * @param testName   the test method, or the test's own title
 * @param lineStart  where the test case starts in {@code file}, 1-based; null until a locator says
 * @param lineEnd    where it ends, inclusive; null until a locator says
 */
@RegisterForReflection
public record TestCoordinates(
        String language, String tool, RepositoryRef repository, String commitSha, String file,
        String className, String testName, Integer lineStart, Integer lineEnd) {

    public TestCoordinates withLines(int start, int end) {
        return new TestCoordinates(language, tool, repository, commitSha, file, className, testName, start, end);
    }
}
