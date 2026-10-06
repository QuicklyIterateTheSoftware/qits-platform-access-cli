package eu.wohlben.qits.cli.access.report;

/**
 * What a run compares against: the gating run of the release request that produced the repository's
 * newest released version. Read from qits-ci as a tree, never bound, so it needs no registration.
 *
 * @param version          the released version, which is also its tag
 * @param runId            that release request's QA run, whose reports are the baseline's
 * @param releaseRequestId the release request
 * @param tagSha           the commit the tag names
 */
public record Baseline(String version, String runId, String releaseRequestId, String tagSha) {
}
