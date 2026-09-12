package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.daemon.SessionDaemonCommand;
import eu.wohlben.qits.cli.access.events.EventsCommand;
import eu.wohlben.qits.cli.access.git.GitCredentialCommand;
import eu.wohlben.qits.cli.access.git.GitLoginCommand;
import eu.wohlben.qits.cli.access.help.HelpCommand;
import eu.wohlben.qits.cli.access.login.LoginCommand;
import eu.wohlben.qits.cli.access.observe.ObserveCommand;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.projects.ProjectsCommand;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestCommand;
import eu.wohlben.qits.cli.access.projects.RepositoriesCommand;
import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

/**
 * The {@code qits} command. With no command named, it shows what there is.
 * <p>
 * The help texts of all commands are the one source of SKILL.md ({@code qits help skill}). The
 * first description line here is its trigger, and the footer is its platform rules.
 */
@TopCommand
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        subcommands = {LoginCommand.class, SessionDaemonCommand.class, ProjectsCommand.class,
                RepositoriesCommand.class, ReleaseRequestCommand.class, EventsCommand.class,
                ObserveCommand.class, GitLoginCommand.class, GitCredentialCommand.class, HelpCommand.class},
        description = {
                "Use for any work on the qits platform from a terminal: signing in, projects and repositories, "
                        + "release requests, domain events, live telemetry, and Git pushes to the platform's git host.",
                "Each command calls the platform through its edge, with the session of `qits login`. "
                        + "`qits <command> --help` shows a command's options, examples and exit codes."},
        footerHeading = "%nPlatform rules:%n",
        footer = {
                "- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.",
                "- Release only through a release request: `qits release-request create`, or `join` to add a "
                        + "branch to an open one. A push releases nothing.",
                "- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from "
                        + "`qits git-credential`.",
                "- qits never prints a token. The one exception is `qits git-credential get`, which Git runs.",
                "- A command that says `Not signed in` or `Session ended` exits with 2: run `qits login`."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
public class AccessCli implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }
}
