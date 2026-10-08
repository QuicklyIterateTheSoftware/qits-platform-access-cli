package eu.wohlben.qits.cli.access.report.screenshots;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

/**
 * One way screenshot baselines are stored in a repository (epic qits-762, Design §1). The scope of the
 * {@code screenshots} report is the storage convention, never the repository's kind: any repository
 * whose committed files one of these identifies is reported. Playwright's {@code *-snapshots/} is a
 * later implementation, one more entry in {@link ScreenshotConventions}.
 */
public interface ScreenshotConvention {

    /** The wire name, as the payload's {@code conventions} and entries spell it: {@code vitest-browser}. */
    String id();

    /** The tool that writes the baselines: {@code vitest}. */
    String tool();

    /**
     * True when THIS step rendered the screenshots, so a run reports them once, not once per QA step.
     *
     * @param root the step's tree
     */
    boolean renderedHere(Path root);

    /**
     * A committed baseline image of this convention, identified; empty when the path is not this
     * convention's file.
     *
     * @param repoPath a blob's path in the repository, forward slashes, as {@code git ls-tree} lists it
     */
    Optional<ScreenshotId> identify(String repoPath);

    /** The renderer fingerprint file among {@code repoPaths}, when the convention has one. */
    Optional<String> rendererRecord(Set<String> repoPaths);
}
