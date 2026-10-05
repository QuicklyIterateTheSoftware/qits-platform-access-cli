package eu.wohlben.qits.cli.session;

import java.util.Map;

/**
 * Which of the CLI's three homes this process is in.
 * <p>
 * A workstation, where a person signed in with a browser and the session lives in a file; a
 * container on the platform, where there is no browser and no person and the credential is the
 * commissioned client pair the container was injected with; or a workspace on a runner node, which
 * has neither and is handed a token of its own ({@code QITS_TOKEN}) and reaches the platform through
 * the public edge, because no wire alias resolves there.
 * <p>
 * They are never mixed: in-platform never reads the session file, a workstation never mints with a
 * client secret, and the token home does neither. The decision is a function of the environment, which does not change while
 * a process runs, so it is made once and holds for every command in it.
 */
public enum Mode {

    /** A person's machine: the session file from {@code qits login}. */
    WORKSTATION,

    /** A workspace container: the commissioned client, minted at the internal idp. */
    IN_PLATFORM,

    /** A workspace on a runner node: {@code QITS_TOKEN} as it is, sent to the public vhosts. */
    EDGE_TOKEN;

    /**
     * The workspace's own token. Non-blank, it decides the home ahead of everything else — the
     * override and the pair included: a container that was handed one was addressed on purpose, and
     * a runner node resolves none of the addresses the other two homes would dial.
     */
    public static final String TOKEN = "QITS_TOKEN";

    public static final String CLIENT_ID = "QITS_COMMISSIONED_CLIENT_ID";
    public static final String CLIENT_SECRET = "QITS_COMMISSIONED_CLIENT_SECRET";

    /**
     * An explicit answer, honoured in both directions — but never the signal. No container on the
     * platform sets it today, so a CLI that waited for it would never notice where it is. It chooses
     * between the workstation and the pair only; it does not beat {@link #TOKEN}.
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
        return given(env, CLIENT_ID) && given(env, CLIENT_SECRET) ? IN_PLATFORM : WORKSTATION;
    }

    /** The commissioned pair and the wire aliases. False in the token home, which has neither. */
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
