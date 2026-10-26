package eu.wohlben.qits.cli.access.targets;

/**
 * What each qits-system-service targets door lets through, in the words every command's help
 * uses, so the help and SKILL.md say it the same way. They state the service's {@code @RolesAllowed}
 * as it is (dossier "Targets in qits-system: data, doors, allocation"): a door that changes, changes
 * here.
 */
final class Doors {

    /**
     * listTargets, getTarget, requestTargetHealthCheck: qits:admin, qits:admin-agent, qits:desk-admin,
     * qits:system, qits:agent.
     */
    static final String READ = "Any credential may call it, qits:agent included.";

    /**
     * createTarget, rotateTargetRegistrationToken, patchTarget, deleteTarget: qits:admin,
     * qits:admin-agent, qits:desk-admin (epic qits-1621) or qits:system.
     */
    static final String WRITE = "It needs qits:admin, qits:admin-agent, qits:desk-admin or qits:system, which a "
            + "person's token, the delivery desk's or qits-system's own carries. qits:agent alone is refused (HTTP "
            + "403) by design.";

    /**
     * greenlightTarget, requestTargetLoginCheck, createSignIn, getSignIn, postSignInCode,
     * deleteSignIn: qits:admin, qits:admin-agent or qits:desk-admin only.
     */
    static final String ADMIN_ONLY = "It needs qits:admin, qits:admin-agent or qits:desk-admin, which a person's "
            + "token or the delivery desk's carries. qits:agent alone and qits:system are refused (HTTP 403) by "
            + "design.";

    /**
     * listLeases: qits:system sees its own leases; qits:admin, qits:admin-agent, qits:desk-admin and
     * qits:agent see every lease.
     */
    static final String LEASES = "qits:admin, qits:admin-agent, qits:desk-admin and qits:agent read every lease; "
            + "qits:system reads only its own.";

    private Doors() {
    }
}
