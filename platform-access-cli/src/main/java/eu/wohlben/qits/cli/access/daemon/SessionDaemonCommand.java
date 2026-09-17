package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;

@TuiCommand(interaction = Interaction.STREAMING)
@CommandLine.Command(name = "session-daemon", mixinStandardHelpOptions = true,
        description = {
                "Keep the session from `qits login` fresh for as long as this runs. Start it once per workstation "
                        + "and leave it running.",
                "It refreshes the access token shortly before it expires and writes the new pair to "
                        + "$XDG_CONFIG_HOME/qits/t.json. It logs one line per event to stderr, and stops on SIGTERM "
                        + "or SIGINT."},
        footerHeading = HelpText.EXAMPLES,
        footer = {
                "  qits session-daemon &",
                "  systemctl --user enable --now qits-session-daemon",
                "",
                "- The systemd unit is in the README. Only one daemon runs at a time; a second one exits with 1.",
                "- When the session ends (revoked or expired), it says so, keeps running, and carries on after the "
                        + "next `qits login`.",
                "- Without it, a command refreshes an access token that is about to expire before it calls the "
                        + "platform."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:Stopped by SIGTERM or SIGINT.", "1:Another qits session-daemon is running.",
                "2:--margin is not between 0 and 300."})
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
