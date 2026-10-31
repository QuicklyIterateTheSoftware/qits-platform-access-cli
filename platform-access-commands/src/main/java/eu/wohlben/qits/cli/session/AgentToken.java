package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.session.TokenClaims;

/**
 * How an agent's token is named and what it says when a door refuses it, whoever holds it: the
 * workspace's own token ({@link TokenCredential}) and an agent's bearer the MCP service runs a
 * command with ({@link RequestCredential}) must say exactly the same, so an agent meets the same
 * sentence whether it called the platform itself or through the MCP service.
 * <p>
 * Neither sentence is a guess made before the call: the service decides, and this only says what
 * the caller was holding when it decided.
 */
final class AgentToken {

    /** The audience every agent's token carries, and the one every qits service accepts. */
    static final String DEFAULT_AUDIENCE = "qits-platform";

    /** The role every agent's token carries: it reads across the estate and writes nowhere. */
    static final String AGENT_ROLE = "qits:agent";

    /**
     * <b>403</b> is the one an agent will meet often. The credential reads across the estate and
     * writes nowhere, which is the correct answer and not a fault to work around. It is <b>not</b>
     * worked around with {@code X-Qits-User} / {@code X-Qits-Roles}. Those headers are what the
     * gateway asserts <i>about</i> a caller; a client that writes them asserts a role it does not
     * hold, which is a privilege escalation with a friendly name. An agent that needs a door this
     * credential cannot open is granted the audience, not given a header.
     * <p>
     * <b>401</b> names the audience the token carries and nothing more. The audience is never judged
     * here before dialling: every qits service accepts the one audience {@code qits-platform}, and
     * an agent's token carries exactly that, so only the service can say no — and when it does, this
     * line names what was sent.
     */
    static String explain(int status, String audience) {
        if (status == 403) {
            return "403 - this credential is " + AGENT_ROLE + ", which reads but does not write";
        }
        if (status == 401) {
            return "401 - this service did not accept the workspace credential, which asked the idp"
                    + " for the audience " + audience;
        }
        return null;
    }

    /** {@code agent (qits:agent)}: how an agent's token is named, whoever holds it. */
    static String who(TokenClaims claims) {
        return "agent (" + claims.role().orElse("no role") + ")";
    }

    private AgentToken() {
    }
}
