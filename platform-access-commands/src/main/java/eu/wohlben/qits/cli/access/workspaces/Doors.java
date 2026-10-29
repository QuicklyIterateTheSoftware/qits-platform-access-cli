package eu.wohlben.qits.cli.access.workspaces;

/**
 * What each kind of qits-workspaces door lets through, in the words every command's help uses, so
 * the help and SKILL.md say it the same way. They state the service's {@code @RolesAllowed} as it
 * is: a door that changes, changes here.
 */
final class Doors {

    /**
     * A read on the JSON API: qits:admin, qits:admin-agent, qits:desk-admin and qits:agent (and
     * qits:system on most).
     */
    static final String READ = "Any credential may read it, qits:agent included.";

    /**
     * A write on the JSON API at {qits:admin, qits:admin-agent, qits:desk-admin} (some add
     * qits:system). The desk-admin stays a bound desk: its own project only (DeskBinding).
     */
    static final String WRITE = "It needs qits:admin, qits:admin-agent or qits:desk-admin (the delivery desk, within "
            + "its own project), which a person's token or the desk's carries. qits:agent alone is refused (HTTP "
            + "403) by design: an agent keeps every read and loses the writes.";

    /**
     * The container proxy: qits-workspaces checks the caller against the slot there (qits-1386).
     * qits:desk-admin is not one of the roles that reach any slot: the proxy is a shell and a file
     * writer, where a push is one command away, so the desk is judged as the qits:agent it holds.
     */
    static final String PROXY = "It goes through qits-workspaces' container proxy to the slot's daemon. A person, "
            + "qits:system, qits:admin or qits:admin-agent may reach any slot; qits:agent only its own workspace's. "
            + "qits:desk-admin is judged as qits:agent there, because the slot is a shell that can push.";

    /**
     * The agent control doors of epic qits-1667 (prompt, interrupt, stop, restart): the desk roles are
     * held to their own project there, and qits:system may call them too.
     */
    static final String CONTROL = "It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, "
            + "within its own project), or qits:system. qits:agent alone is refused (HTTP 403) by design.";

    /** {@code yieldAgent}: the control roles, and an agent for itself. */
    static final String YIELD = "It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:desk (a desk, "
            + "within its own project), or qits:system; qits:agent may yield only its own agent.";

    /**
     * The agent proxy, {@code /workspaces/agent/{id}/…} (epic qits-1667): checked against the agent,
     * not the slot. The desk roles read every agent's daemon there but open no socket.
     */
    static final String AGENT_PROXY = "It goes through qits-workspaces' agent proxy to the daemon of the slot the "
            + "agent is placed in now. qits:system, qits:admin and qits:admin-agent reach every agent; qits:agent "
            + "only its own; qits:desk and qits:desk-admin read every agent's and open no terminal.";

    private Doors() {
    }
}
