package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.session.TokenClaims;

/**
 * A bearer somebody else already holds, handed in whole: the MCP service runs a command for its
 * caller with the caller's own token, so the platform sees the caller and nobody else.
 * <p>
 * <b>It never mints and never refreshes.</b> There is no client secret and no session file behind
 * it, only the token. An expired one is the caller's to renew; the service it is sent to answers
 * 401, and that is the honest answer to pass back.
 * <p>
 * The claims are read without checking the signature. Whoever built this has already validated the
 * token (the MCP service does, before any tool runs), and what is read here only names the holder —
 * nothing here decides access.
 */
public final class RequestCredential implements Credential {

    private final String token;
    private final TokenClaims claims;

    public RequestCredential(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("A request credential needs the caller's token.");
        }
        this.token = token;
        this.claims = TokenClaims.of(token);
    }

    @Override
    public String bearer() {
        return token;
    }

    /** {@code agent (qits:agent)} for an agent's token, {@code signed in as jan} for a person's. */
    @Override
    public String who() {
        if (agent()) {
            return AgentCredential.who(claims);
        }
        return claims.who().map(name -> "signed in as " + name).orElse("signed in");
    }

    /**
     * An agent's token gets the workspace credential's sentences, word for word: an agent meets the
     * same refusal whether it called the platform itself or through the MCP service. A person's
     * token has nothing to add, and the service's own answer speaks.
     */
    @Override
    public String explain(int status) {
        if (!agent()) {
            return null;
        }
        String audience = claims.audiences().contains(AgentCredential.DEFAULT_AUDIENCE)
                ? AgentCredential.DEFAULT_AUDIENCE
                : String.join(",", claims.audiences());
        return AgentCredential.explainAgent(status, audience);
    }

    private boolean agent() {
        return claims.groups().contains(AgentCredential.AGENT_ROLE);
    }

    @Override
    public String toString() {
        return "RequestCredential[" + claims.who().orElse("?") + "]";
    }
}
