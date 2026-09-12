package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.daemon.SessionDaemonCommand;
import eu.wohlben.qits.cli.access.login.LoginCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

/** The {@code qits} command. With no command named, it shows what there is. */
@TopCommand
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        subcommands = {LoginCommand.class, SessionDaemonCommand.class},
        description = "Access to the qits platform from this workstation.")
public class AccessCli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
