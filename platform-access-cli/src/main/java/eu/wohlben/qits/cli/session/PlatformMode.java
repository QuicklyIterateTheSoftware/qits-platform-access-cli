package eu.wohlben.qits.cli.session;

import jakarta.inject.Singleton;

import java.util.Map;

/**
 * The mode, decided once when the process starts and held here.
 * <p>
 * {@link Mode#of} is a function of the environment and the environment does not change while a
 * process runs, so nothing could disagree with this bean; it exists so that the decision has one
 * place and one moment, and so that a command asks rather than works it out again.
 */
@Singleton
public class PlatformMode {

    private final Mode mode;
    private final Map<String, String> env;

    public PlatformMode() {
        this(System.getenv());
    }

    public PlatformMode(Map<String, String> env) {
        this.env = env;
        this.mode = Mode.of(env);
    }

    public Mode mode() {
        return mode;
    }

    public boolean inPlatform() {
        return mode.inPlatform();
    }

    /** The addresses this mode dials. {@code idpUrl} is the session's, and null inside. */
    public PlatformEndpoints endpoints(String idpUrl) {
        return new PlatformEndpoints(mode, env, idpUrl);
    }
}
