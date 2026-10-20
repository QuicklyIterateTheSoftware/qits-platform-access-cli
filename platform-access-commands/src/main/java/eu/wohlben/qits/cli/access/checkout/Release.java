package eu.wohlben.qits.cli.access.checkout;

/**
 * One release of one repository, as a finalized release request names it.
 *
 * @param version the released version, which is also the tag the release host carries
 * @param sha     the commit the release was cut from, or null to read it from the tag. A finalized
 *                release request names no such commit (its {@code mergedSha} is the fold's tip, not
 *                the tagged commit), so the watch always passes null; a missing commit is never a
 *                reason to refuse.
 */
public record Release(String version, String sha) {
}
