package eu.wohlben.qits.cli.access.report;

/**
 * What a run compares against: the gating run of the release request that produced the repository's
 * newest released version. Read from qits-ci as a tree, never bound, so it needs no registration.
 *
 * @param version          the released version, which is also its tag
 * @param runId            that release request's QA run, whose reports are the baseline's
 * @param releaseRequestId the release request
 * @param tagSha           the commit the tag names
 * @param releaseRequestQualifiedId the release request's logical id, {@code <repository>-rr-<n>};
 *                         null from a qits-ci older than qits-1158
 */
public record Baseline(String version, String runId, String releaseRequestId, String tagSha,
                       String releaseRequestQualifiedId) {

    /** A baseline without the request's logical id. */
    public Baseline(String version, String runId, String releaseRequestId, String tagSha) {
        this(version, runId, releaseRequestId, tagSha, null);
    }
}
