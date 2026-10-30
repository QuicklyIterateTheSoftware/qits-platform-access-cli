package eu.wohlben.qits.cli.access.artifacts;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits artifacts fetch}: published artifacts written to the caller's disk. Everything under it
 * writes local files, so it is {@link Interaction#LOCAL}: the MCP service would write them into its
 * own container, where nobody reads them.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "fetch", mixinStandardHelpOptions = true,
        subcommands = {DeployedPactsCommand.class},
        description = "Write published artifacts to a local directory. deployed-pacts: the consumer pacts a "
                + "provider has to keep honouring, one per consumer version that is deployed or kept for a rollback.")
public class FetchCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
