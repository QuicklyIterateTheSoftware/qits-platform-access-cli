package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;

/**
 * What a call to the platform is made with, whichever home this is.
 * <p>
 * A workstation's is the session from {@code qits login}, kept fresh in a file; a container's is a
 * token minted from its commissioned client and held in memory only. One method, so no caller has
 * to know which it is holding — and the two are never mixed, because the mode picks one at startup.
 */
public interface Credential {

    /** The access token to send, minted or refreshed first if it is about to expire. */
    String bearer() throws CliFailure, InterruptedException;

    /**
     * Refuse before the call when this credential is not granted the service being dialled.
     * <p>
     * A token the idp would not have issued for a service is not worth sending to it: the answer
     * would be a 401 that says nothing about why. A workstation session has no such limit and does
     * nothing here.
     *
     * @param audience the service's own name on the wire, which is the audience it accepts
     */
    default void checkAudience(String audience) throws CliFailure, InterruptedException {
    }

    /** What to call the holder on screen: {@code signed in as jan}, {@code agent (qits:agent)}. */
    default String who() throws CliFailure, InterruptedException {
        return "";
    }

    /**
     * What this credential has to say about a status the platform answered with, or null when it
     * has nothing to add.
     * <p>
     * A refusal that is the credential's own doing reads better as a sentence than as an error
     * body: an agent that meets a {@code 403} should be told what it holds, not sent looking for a
     * bug that is not there.
     */
    default String explain(int status) {
        return null;
    }
}
