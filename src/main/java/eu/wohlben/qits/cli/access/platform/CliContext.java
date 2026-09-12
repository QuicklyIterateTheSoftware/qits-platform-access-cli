package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.daemon.StopSignals;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.SessionFile;

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
}
