package eu.wohlben.qits.cli.access.help;

import picocli.CommandLine;

/** Help for agents. Hidden: a person reads `qits --help`, an agent reads `qits help skill`. */
@CommandLine.Command(name = "help", hidden = true, mixinStandardHelpOptions = true,
        subcommands = SkillCommand.class,
        description = "Help for agents.")
public class HelpCommand implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
