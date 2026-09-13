package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.daemon.StopSignals;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.AgentCredential;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.PlatformEndpoints;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * What a platform command takes from the world. The real one is {@link #system()}; a test gives
 * its own, so it can set the environment, feed stdin and read the output without starting a
 * process.
 *
 * @param onStop installs what SIGTERM and SIGINT do
 */
public record CliContext(
        Map<String, String> env,
        InputStream in,
        PrintStream out,
        PrintStream err,
        Clock clock,
        Sleeper sleeper,
        Function<String, TokenClient> idpFor,
        Consumer<Runnable> onStop) {

    /** UTF-8 whatever the locale says, so a dash in a message stays a dash. */
    public static CliContext system() {
        return new CliContext(System.getenv(), System.in,
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8),
                new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8),
                Clock.systemUTC(), Sleeper.real(), TokenClient::new, StopSignals::install);
    }

    public SessionFile sessionFile() {
        return SessionFile.fromEnvironment(env);
    }

    public AccessTokens tokens() {
        return new AccessTokens(sessionFile(), clock, idpFor);
    }

    /**
     * What this process calls the platform with: the session file outside, the workspace
     * credential inside. The two are never mixed — in-platform never opens the session file, and a
     * workstation never mints with a client secret.
     */
    public Credential credential() {
        return mode().inPlatform() ? AgentCredential.of(env, clock) : tokens();
    }

    /**
     * The session's idp address, which is where a workstation's service addresses come from. Null
     * inside the platform, where they come from the wire aliases instead and there is no session to
     * read.
     */
    public String idpUrl() throws CliFailure, InterruptedException {
        return mode().inPlatform() ? null : tokens().session().idpUrl();
    }

    /** Which of the CLI's two homes this is. Decided from the environment, which does not change. */
    public Mode mode() {
        return Mode.of(env);
    }

    /** Where the services are in this home. {@code idpUrl} is the session's, and null inside. */
    public PlatformEndpoints endpoints(String idpUrl) {
        return new PlatformEndpoints(mode(), env, idpUrl);
    }
}
