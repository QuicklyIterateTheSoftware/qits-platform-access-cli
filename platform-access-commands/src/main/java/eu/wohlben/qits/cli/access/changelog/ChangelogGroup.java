package eu.wohlben.qits.cli.access.changelog;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits changelog}: what reads the changelogs every release publishes ({@code qits artifacts
 * publish changelog}). Today that is one CI step's need, the bump message.
 */
@TuiCommand(interaction = Interaction.CI_ONLY)
@CommandLine.Command(name = "changelog", mixinStandardHelpOptions = true,
        subcommands = {BumpMessageCommand.class},
        description = "Release changelogs, as the docs store keeps them (@changelog/<repository>, one CHANGELOG.md "
                + "per version). bump-message writes a dependency bump's commit message from them, in a CI step.")
public class ChangelogGroup implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
