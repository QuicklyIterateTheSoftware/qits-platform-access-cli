package eu.wohlben.qits.cli.access.contracts;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits contracts} — a repository's contracts, read from its own files. Its only command today is
 * {@code coverage}, which qits-ci runs in a java service's QA step (qits-1940).
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "contracts", mixinStandardHelpOptions = true,
        subcommands = {CoverageCommand.class},
        description = "A repository's contracts, read from its own files: `qits contracts coverage` names every "
                + "operation of its OpenAPI document that no golden master records.")
public class ContractsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
