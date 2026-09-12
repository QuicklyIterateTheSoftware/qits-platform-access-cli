package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.SessionFile;
import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "session-daemon", mixinStandardHelpOptions = true,
        description = {
                "Keep the session from `qits login` fresh for as long as this runs.",
                "Refreshes the access token shortly before it expires and writes the new pair to "
                        + "$XDG_CONFIG_HOME/qits/t.json. Logs to stderr. Stops on SIGTERM or SIGINT."})
public class SessionDaemonCommand implements Callable<Integer> {

    @CommandLine.Option(names = "--margin", paramLabel = "<seconds>", defaultValue = "30",
            description = "Refresh this many seconds before the access token expires, 0 to 300. "
                    + "Default: ${DEFAULT-VALUE}.")
    long marginSeconds;

    @Override
    public Integer call() throws Exception {
        // A margin near the access token's lifetime (15 minutes) would refresh without a pause.
        if (marginSeconds < 0 || marginSeconds > 300) {
            System.err.println("--margin must be between 0 and 300 seconds.");
            return 2;
        }
        PrintStream log = new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8);
        SessionDaemon daemon = new SessionDaemon(SessionFile.fromEnvironment(System.getenv()),
                Duration.ofSeconds(marginSeconds), Clock.systemUTC(), Sleeper.real(), TokenClient::new, log);
        StopSignals.install(daemon::stop);
        return daemon.run();
    }
}
