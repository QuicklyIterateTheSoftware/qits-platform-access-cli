package eu.wohlben.qits.cli.access.targets;

/**
 * What each qits-system-service targets door lets through, in the words every command's help
 * uses, so the help and SKILL.md say it the same way. They state the service's {@code @RolesAllowed}
 * as it is (dossier "Targets in qits-system: data, doors, allocation"): a door that changes, changes
 * here.
 */
final class Doors {

    /** listTargets, getTarget, requestTargetHealthCheck: qits:admin, qits:admin-agent, qits:system, qits:agent. */
    static final String READ = "Any credential may call it, qits:agent included.";

    /**
     * createTarget, rotateTargetRegistrationToken, patchTarget, deleteTarget: qits:admin,
     * qits:admin-agent or qits:system.
     */
    static final String WRITE = "It needs qits:admin, qits:admin-agent or qits:system, which a person's token or "
            + "qits-system's own carries. qits:agent is refused (HTTP 403) by design.";

    /** greenlightTarget, requestTargetLoginCheck: qits:admin or qits:admin-agent only. */
    static final String ADMIN_ONLY = "It needs qits:admin or qits:admin-agent, which a person's token carries. "
            + "qits:agent and qits:system are refused (HTTP 403) by design.";

    /** listLeases: qits:system sees its own leases; qits:admin, qits:admin-agent and qits:agent see every lease. */
    static final String LEASES = "qits:admin, qits:admin-agent and qits:agent read every lease; qits:system reads "
            + "only its own.";

    private Doors() {
    }
}
