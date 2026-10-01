package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.tui.TuiCommand;
import io.quarkus.picocli.runtime.PicocliCommandLineFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import picocli.CommandLine;

/**
 * The binary's command tree: the commands library's {@link AccessCli}, with {@code qits tui} put
 * back.
 * <p>
 * The library leaves {@code tui} out because the screen needs a terminal and that lives here, with
 * JLine. {@code quarkus.picocli.top-command} names {@link AccessCli}; this producer replaces
 * quarkus-picocli's default one, so the {@link CommandLine} {@link Main} is handed is this tree.
 */
@ApplicationScoped
public class QitsCommandLine {

    @Produces
    CommandLine commandLine(PicocliCommandLineFactory factory) {
        return withTui(factory.create());
    }

    /**
     * {@code qits} with {@code tui} in the place it had when it was declared in {@link AccessCli}'s
     * own list: after {@code artifacts}, before {@code help}. picocli appends a subcommand and has no
     * insert, so {@code help} is taken off and added again behind {@code tui}; the order is what
     * {@code qits help skill} and the screen list the commands in. The root declares no inherited
     * option, so adding {@code help} a second time copies nothing onto it twice.
     * <p>
     * Tests build the binary's tree with this too, from a plain {@code new CommandLine(new
     * AccessCli())}.
     */
    public static CommandLine withTui(CommandLine qits) {
        CommandLine help = qits.getCommandSpec().removeSubcommand("help");
        qits.addSubcommand("tui", TuiCommand.class);
        qits.addSubcommand("help", help);
        return qits;
    }
}
