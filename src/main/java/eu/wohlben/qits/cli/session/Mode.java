package eu.wohlben.qits.cli.session;

import java.util.Map;

/**
 * Which of the CLI's two homes this process is in.
 * <p>
 * A workstation, where a person signed in with a browser and the session lives in a file, or a
 * container on the platform, where there is no browser and no person and the credential is the
 * commissioned client pair the container was injected with.
 * <p>
 * The two are never mixed: in-platform never reads the session file, and a workstation never mints
 * with a client secret. The decision is a function of the environment, which does not change while
 * a process runs, so it is made once and holds for every command in it.
 */
public enum Mode {

    /** A person's machine: the session file from {@code qits login}. */
    WORKSTATION,

    /** A workspace container: the commissioned client, minted at the internal idp. */
    IN_PLATFORM;

    public static final String CLIENT_ID = "QITS_COMMISSIONED_CLIENT_ID";
    public static final String CLIENT_SECRET = "QITS_COMMISSIONED_CLIENT_SECRET";

    /**
     * An explicit answer, honoured in both directions — but never the signal. No container on the
     * platform sets it today, so a CLI that waited for it would never notice where it is.
     */
    public static final String OVERRIDE = "QITS_PLATFORM";

    public static Mode of(Map<String, String> env) {
        String override = env.get(OVERRIDE);
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.strip()) ? IN_PLATFORM : WORKSTATION;
        }
        return given(env, CLIENT_ID) && given(env, CLIENT_SECRET) ? IN_PLATFORM : WORKSTATION;
    }

    public boolean inPlatform() {
        return this == IN_PLATFORM;
    }

    private static boolean given(Map<String, String> env, String name) {
        String value = env.get(name);
        return value != null && !value.isBlank();
    }
}
