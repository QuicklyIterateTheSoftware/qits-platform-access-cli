package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.daemon.StopSignals;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.session.AgentCredential;
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.PlatformEndpoints;
import eu.wohlben.qits.cli.session.TokenCredential;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What a platform command takes from the world. The real one is {@link #system()}; a test gives
 * its own, so it can set the environment, feed stdin and read the output without starting a
 * process.
 *
 * @param onStop installs what SIGTERM and SIGINT do
 * @param credentials what a call is made with. Null is today's choice by mode (see {@link
 *                    #credential()}); a host that runs the commands for somebody else — the MCP
 *                    service, which forwards its caller's bearer — hands its own.
 */
public record CliContext(
        Map<String, String> env,
        InputStream in,
        PrintStream out,
        PrintStream err,
        Clock clock,
        Sleeper sleeper,
        Function<String, TokenClient> idpFor,
        Consumer<Runnable> onStop,
        Supplier<Credential> credentials) {

    /**
     * Set, as a system property or in the environment, in the MCP service. That process runs the
     * commands for whoever called it, so a context it forgot to hand a command must fail loudly: the
     * fallback would read the service's own environment and call the platform as the service
     * rather than as the caller. Its value does not matter, only that it is there.
     */
    public static final String MCP_SERVICE = "QITS_MCP_SERVICE";

    public CliContext {
        if (credentials == null) {
            // The components, not the accessors: a compact constructor has not assigned them yet.
            Map<String, String> environment = env;
            Clock time = clock;
            Function<String, TokenClient> idps = idpFor;
            credentials = () -> switch (Mode.of(environment)) {
                case EDGE_TOKEN -> new TokenCredential(environment);
                case IN_PLATFORM -> AgentCredential.of(environment, time);
                case WORKSTATION -> new AccessTokens(SessionFile.fromEnvironment(environment), time, idps);
            };
        }
    }

    /** The context every caller had before a credential could be carried: the credential by mode. */
    public CliContext(Map<String, String> env, InputStream in, PrintStream out, PrintStream err, Clock clock,
                      Sleeper sleeper, Function<String, TokenClient> idpFor, Consumer<Runnable> onStop) {
        this(env, in, out, err, clock, sleeper, idpFor, onStop, null);
    }

    /** This one, calling the platform with {@code credential} whatever the mode says. */
    public CliContext withCredential(Credential credential) {
        return new CliContext(env, in, out, err, clock, sleeper, idpFor, onStop, () -> credential);
    }

    /**
     * The process's own context. UTF-8 whatever the locale says, so a dash in a message stays a dash.
     *
     * @throws IllegalStateException inside the MCP service ({@link #MCP_SERVICE}), which must never
     *                               fall back to its own environment
     */
    public static CliContext system() {
        if (System.getProperty(MCP_SERVICE) != null || System.getenv(MCP_SERVICE) != null) {
            throw new IllegalStateException("A command ran without a context in the MCP service ("
                    + MCP_SERVICE + " is set): it would have called the platform as the service, not the caller.");
        }
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
     * credential inside, the workspace token in the token home — a runner node, or an admin or
     * editor workspace placed directly on qits-containers — unless the context was built with a
     * credential of its own. The homes are never mixed — in-platform never opens the session file,
     * a workstation never mints with a client secret, and the token home does neither.
     */
    public Credential credential() {
        return credentials.get();
    }

    /**
     * The session's idp address, which is where a workstation's service addresses come from. Null
     * inside the platform, where they come from the wire aliases instead and there is no session to
     * read. In the token home it is the idp's public vhost, {@code https://idp.qits.<QITS_DOMAIN>/idp},
     * so every address derived from it is the vhost a workstation would derive.
     */
    public String idpUrl() throws CliFailure, InterruptedException {
        Mode mode = mode();
        if (mode.edgeToken()) {
            return PlatformEndpoints.edge("idp", env) + "/idp";
        }
        return mode.inPlatform() ? null : tokens().session().idpUrl();
    }

    /**
     * Whether stdin is a person's terminal rather than a pipe, a file or nothing. A command that
     * reads a document on stdin asks first, so that typing it bare shows what it wants instead of
     * waiting for input nobody knows to give.
     * <p>
     * Only the process's own stdin can be a terminal: a stream a test hands in never is. The answer
     * is read from where {@code /proc/self/fd/0} points (a {@code /dev/pts/} or {@code /dev/tty}
     * device), not from {@link System#console()}, which also asks about stdout and, since Java 22,
     * answers even when neither is a terminal; a link read works the same in the native binary.
     * Where there is no {@code /proc} the answer is no, and the command reads stdin.
     */
    public boolean stdinIsTerminal() {
        if (in != System.in) {
            return false;
        }
        try {
            String device = Files.readSymbolicLink(Path.of("/proc/self/fd/0")).toString();
            return device.startsWith("/dev/pts/") || device.startsWith("/dev/tty") || device.equals("/dev/console");
        } catch (IOException | UnsupportedOperationException | SecurityException unknown) {
            return false;
        }
    }

    /** Which of the CLI's three homes this is. Decided from the environment, which does not change. */
    public Mode mode() {
        return Mode.of(env);
    }

    /** Where the services are in this home. {@code idpUrl} is the session's, and null inside. */
    public PlatformEndpoints endpoints(String idpUrl) {
        return new PlatformEndpoints(mode(), env, idpUrl);
    }
}
