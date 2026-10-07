package eu.wohlben.qits.cli.access.database;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits database} — a repository's database, read from its build. Its only command today is
 * {@code diagram}, the entity diagram the entity-diagram automation keeps under {@code docs/database/}.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "database", mixinStandardHelpOptions = true,
        subcommands = {DiagramCommand.class},
        description = "A repository's database, read from its compiled code: `qits database diagram` writes the "
                + "diagram of its entities under docs/database/, one Mermaid file per persistence unit.")
public class DatabaseCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
