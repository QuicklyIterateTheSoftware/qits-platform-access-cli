package eu.wohlben.qits.cli.session;

import java.util.Map;

/**
 * Which of the CLI's three homes this process is in.
 * <p>
 * A workstation, where a person signed in with a browser and the session lives in a file; a
 * process that runs commands for somebody else — the MCP service, which hands every command its
 * caller's own bearer — where services are dialled at the public vhosts of {@code QITS_DOMAIN};
 * or a workspace handed a token of its own ({@code QITS_TOKEN}) — every agent container, a runner
 * node, an admin or editor workspace placed directly on qits-containers — which reaches the
 * platform through the public edge, because only the edge exchanges that token.
 * <p>
 * They are never mixed: only a workstation ever reads the session file. The decision is a
 * function of the environment, which does not change while a process runs, so it is made once and
 * holds for every command in it.
 */
public enum Mode {

    /** A person's machine: the session file from {@code qits login}. */
    WORKSTATION,

    /**
     * Run for somebody else, with the credential its host hands each command — the MCP service and
     * its caller's bearer — dialling the public vhosts of {@code QITS_DOMAIN}, as every home does. Only {@link #OVERRIDE} chooses it.
     */
    IN_PLATFORM,

    /**
     * A workspace handed a token of its own — a runner node, or an admin or editor workspace placed
     * directly on qits-containers: {@code QITS_TOKEN} as it is, sent to the public vhosts.
     */
    EDGE_TOKEN;

    /**
     * The workspace's own token. Non-blank, it decides the home ahead of everything else — the
     * override included: a container that was handed one was addressed on purpose, and a workspace
     * carrying one has to dial the public edge, the only place that token is accepted.
     */
    public static final String TOKEN = "QITS_TOKEN";

    /**
     * An explicit answer, honoured in both directions, and the only way into {@link #IN_PLATFORM}:
     * the MCP service sets it for every command it runs ({@code ToolRunner}). It chooses between the
     * workstation and in-platform only; it does not beat {@link #TOKEN}. Unset, the home is a
     * workstation.
     */
    public static final String OVERRIDE = "QITS_PLATFORM";

    public static Mode of(Map<String, String> env) {
        if (given(env, TOKEN)) {
            return EDGE_TOKEN;
        }
        String override = env.get(OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.strip()) ? IN_PLATFORM : WORKSTATION;
        }
        return WORKSTATION;
    }

    /** A handed credential, from the MCP service. False in the token home, which holds its own. */
    public boolean inPlatform() {
        return this == IN_PLATFORM;
    }

    /** {@code QITS_TOKEN} and the public vhosts of {@code QITS_DOMAIN}. */
    public boolean edgeToken() {
        return this == EDGE_TOKEN;
    }

    private static boolean given(Map<String, String> env, String name) {
        String value = env.get(name);
        return value != null && !value.isBlank();
    }
}
