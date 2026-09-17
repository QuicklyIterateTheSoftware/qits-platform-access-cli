package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;

/**
 * What a call to the platform is made with, whichever home this is.
 * <p>
 * A workstation's is the session from {@code qits login}, kept fresh in a file; a container's is a
 * token minted from its commissioned client and held in memory only. One method, so no caller has
 * to know which it is holding — and the two are never mixed, because the mode picks one at startup.
 * <p>
 * There is deliberately nothing here that judges, before a call, whether this credential may dial a
 * service. There used to be: it read the minted token's {@code aud} claim and refused any service
 * not named in it. That was a false negative, and it cost {@code qits observe} its whole agent
 * audience — every qits service also accepts {@code qits-platform}, which is exactly what an agent's
 * token carries. Only the service knows who it accepts. Dial it, and let {@link #explain(int)} do
 * the talking if it says no.
 */
public interface Credential {

    /** The access token to send, minted or refreshed first if it is about to expire. */
    String bearer() throws CliFailure, InterruptedException;

    /** What to call the holder on screen: {@code signed in as jan}, {@code agent (qits:agent)}. */
    default String who() throws CliFailure, InterruptedException {
        return "";
    }

    /**
     * What this credential has to say about a status the platform answered with, or null when it
     * has nothing to add.
     * <p>
     * A refusal that is the credential's own doing reads better as a sentence than as an error
     * body: an agent that meets a {@code 403} or a {@code 401} should be told what it holds, not
     * sent looking for a bug that is not there. This is the only place that says it, and it says it
     * after the service has spoken — never as a guess beforehand.
     */
    default String explain(int status) {
        return null;
    }
}
