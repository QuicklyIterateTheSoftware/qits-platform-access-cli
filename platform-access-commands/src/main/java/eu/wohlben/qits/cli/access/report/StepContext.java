package eu.wohlben.qits.cli.access.report;

import java.nio.file.Path;
import java.util.Optional;

/**
 * What a kind may know about the step. Built once per submit, from the step's environment.
 *
 * @param root         the step's tree, where the reports are looked for and file paths are relative to
 * @param runId        {@code QITS_CI_RUN_ID}
 * @param stepIndex    {@code QITS_CI_STEP_INDEX}
 * @param repository   {@code QITS_CI_PROJECT_ID} and {@code QITS_CI_REPO_NAME}
 * @param commitSha    {@code QITS_CI_SHA}: the fold's commit, the one the step built
 * @param exitCode     what the step's declared script exited with
 * @param baseline     from {@code GET /ci/api/runs/{runId}/baseline}; empty for a first release, or a
 *                     version from before the reports existed
 * @param changedLines the lines the fold changed against the baseline's tag; unavailable without a
 *                     baseline
 * @param locators     where a failing test case sits in its file
 * @param baselineTag  the baseline's tag in the step's tree, fetched on first use: for a kind that
 *                     reads a file as it was at the baseline. {@link BaselineTag#none()} without one
 */
public record StepContext(
        Path root, String runId, int stepIndex, RepositoryRef repository, String commitSha, int exitCode,
        Optional<Baseline> baseline,
        ChangedLines changedLines,
        TestCaseLocators locators,
        BaselineTag baselineTag) {

    /** A step with no baseline tag to read files at; the kinds that diff use {@link #changedLines}. */
    public StepContext(Path root, String runId, int stepIndex, RepositoryRef repository, String commitSha,
                       int exitCode, Optional<Baseline> baseline, ChangedLines changedLines,
                       TestCaseLocators locators) {
        this(root, runId, stepIndex, repository, commitSha, exitCode, baseline, changedLines, locators,
                BaselineTag.none());
    }

    /** {@code file} relative to {@link #root}, with forward slashes, as the payloads spell a path. */
    public String relative(Path file) {
        String text = root.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize()).toString();
        return text.replace('\\', '/');
    }
}
