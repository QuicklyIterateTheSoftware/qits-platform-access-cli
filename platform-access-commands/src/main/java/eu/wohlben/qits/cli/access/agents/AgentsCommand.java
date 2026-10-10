package eu.wohlben.qits.cli.access.agents;

import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

/**
 * {@code qits agents}: what an agent harness runs on the caller's machine. One group per harness,
 * so a hook says which harness it is for.
 */
@TuiCommand(interaction = Interaction.LOCAL)
@CommandLine.Command(name = "agents", mixinStandardHelpOptions = true,
        subcommands = AgentsCommand.ClaudeCommand.class,
        description = "What an agent harness runs on this machine, one group per harness. A person does not run these.")
public class AgentsCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @CommandLine.Command(name = "claude", mixinStandardHelpOptions = true,
            subcommands = HookCommand.class,
            description = "What Claude Code runs.")
    public static class ClaudeCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "hook", mixinStandardHelpOptions = true,
            subcommands = WorkLinksCommand.class,
            description = "Claude Code's hooks. Each is registered in Claude's settings.json; the help of each shows how.")
    public static class HookCommand implements Runnable {

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }
}
