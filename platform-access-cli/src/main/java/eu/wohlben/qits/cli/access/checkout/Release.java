package eu.wohlben.qits.cli.access.checkout;

/**
 * One release of one repository, as an {@code SCMRelease} event names it.
 *
 * @param version the released version, which is also the tag the release host carries
 * @param sha     the commit the release was cut from, or null. Older events and some producers
 *                leave it out, and the tag says the same thing, so a missing commit is never a
 *                reason to refuse.
 */
public record Release(String version, String sha) {
}
