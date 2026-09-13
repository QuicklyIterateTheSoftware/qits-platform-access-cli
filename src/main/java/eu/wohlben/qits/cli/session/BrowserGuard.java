package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommands;
import picocli.CommandLine;

import java.util.List;

/**
 * Refuses, inside the platform, the commands that need a browser and a person.
 * <p>
 * There is no browser in a container and nobody at a keyboard, and there is nothing for a sign-in to
 * achieve: the container already holds a credential of its own. Saying that in one sentence is
 * better than opening a loopback listener nobody will ever visit.
 * <p>
 * It reads the command's own {@code @TuiCommand(interaction = BROWSER)}, so a command that opens a
 * browser is refused here for saying so once — the same declaration the screen reads. On a
 * workstation this does nothing at all.
 */
public final class BrowserGuard implements CommandLine.IExecutionStrategy {

    public static final String NO_BROWSER =
            "no browser in the platform - the workspace credential is already in use";

    private final CommandLine.IExecutionStrategy next;
    private final Mode mode;

    public BrowserGuard(CommandLine.IExecutionStrategy next, Mode mode) {
        this.next = next;
        this.mode = mode;
    }

    @Override
    public int execute(CommandLine.ParseResult parseResult) {
        List<CommandLine> parsed = parseResult.asCommandLineList();
        CommandLine asked = parsed.getLast();
        // `--help` is not running the command: what a browser command takes has to stay readable
        // from a container, which is where somebody is most likely to be reading it.
        if (!helpAsked(parsed)
                && mode.inPlatform()
                && TuiCommands.interactionOf(asked.getCommandSpec().userObject()) == Interaction.BROWSER) {
            asked.getErr().println(NO_BROWSER);
            asked.getErr().flush();
            return CliFailure.USAGE;
        }
        return next.execute(parseResult);
    }

    private static boolean helpAsked(List<CommandLine> parsed) {
        for (CommandLine line : parsed) {
            CommandLine.ParseResult result = line.getParseResult();
            if (result != null && (result.isUsageHelpRequested() || result.isVersionHelpRequested())) {
                return true;
            }
        }
        return false;
    }
}
