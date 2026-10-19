package eu.wohlben.qits.cli.access.workspaces;

/**
 * What each kind of qits-workspaces door lets through, in the words every command's help uses, so
 * the help and SKILL.md say it the same way. They state the service's {@code @RolesAllowed} as it
 * is: a door that changes, changes here.
 */
final class Doors {

    /** A read on the JSON API: qits:admin, qits:admin-agent and qits:agent (and qits:system on most). */
    static final String READ = "Any credential may read it, qits:agent included.";

    /** A write on the JSON API at {qits:admin, qits:admin-agent} (some add qits:system). */
    static final String WRITE = "It needs qits:admin or qits:admin-agent, which a person's token carries. "
            + "qits:agent is refused (HTTP 403) by design: an agent keeps every read and loses the writes.";

    /** The container proxy: qits-workspaces checks the caller against the slot there (qits-1386). */
    static final String PROXY = "It goes through qits-workspaces' container proxy to the slot's daemon. A person, "
            + "qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's.";

    private Doors() {
    }
}
