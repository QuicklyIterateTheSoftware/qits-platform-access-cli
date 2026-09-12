package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.daemon.SessionDaemonCommand;
import eu.wohlben.qits.cli.access.events.EventsCommand;
import eu.wohlben.qits.cli.access.login.LoginCommand;
import eu.wohlben.qits.cli.access.projects.ProjectsCommand;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestCommand;
import eu.wohlben.qits.cli.access.projects.RepositoriesCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

/** The {@code qits} command. With no command named, it shows what there is. */
@TopCommand
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        subcommands = {LoginCommand.class, SessionDaemonCommand.class, ProjectsCommand.class,
                RepositoriesCommand.class, ReleaseRequestCommand.class, EventsCommand.class},
        description = "Access to the qits platform from this workstation.")
public class AccessCli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
