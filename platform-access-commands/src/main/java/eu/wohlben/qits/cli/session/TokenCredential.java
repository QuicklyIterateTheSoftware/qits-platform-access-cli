package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.util.Map;

/**
 * The credential of a workspace on a runner node: the token it was handed in {@code QITS_TOKEN},
 * sent as it is.
 * <p>
 * Nothing is minted, refreshed or written anywhere. The token is the workspace's own and lives as
 * long as the workspace does, so there is nothing to keep fresh — and, like the commissioned
 * credential, it never touches the session file or the agent's config folder.
 * <p>
 * <b>No message here holds the token.</b>
 */
public final class TokenCredential implements Credential {

    /** Who the token was issued to, when the container was told. Only ever shown, never sent. */
    static final String SUBJECT = "QITS_TOKEN_SUBJECT";

    private final String token;
    private final String subject;

    public TokenCredential(Map<String, String> env) {
        String told = env.get(Mode.TOKEN);
        this.token = told == null ? "" : told.strip();
        String named = env.get(SUBJECT);
        this.subject = named == null || named.isBlank() ? "workspace" : named.strip();
    }

    @Override
    public String bearer() throws CliFailure {
        if (token.isEmpty()) {
            throw new CliFailure("There is no workspace token in this environment (" + Mode.TOKEN + ").",
                    CliFailure.USAGE);
        }
        return token;
    }

    /** {@code agent (qits:agent) via token workspace-352}. */
    @Override
    public String who() {
        return "agent (" + AgentCredential.AGENT_ROLE + ") via token " + subject;
    }

    /**
     * The 403 is the agent's, word for word. The 401 is this credential's own: the token is not
     * minted and cannot expire on the way, so a service refusing it means the platform no longer
     * knows it — the workspace token was deleted, which is what happens when its container is
     * deleted or recreated.
     */
    @Override
    public String explain(int status) {
        if (status == 401) {
            return "401 - the workspace token (" + Mode.TOKEN + ") was not accepted: it has been deleted,"
                    + " which happens when the workspace's container is deleted or recreated";
        }
        return AgentCredential.explainAgent(status, null);
    }

    @Override
    public String toString() {
        return "TokenCredential[" + subject + "]";
    }
}
