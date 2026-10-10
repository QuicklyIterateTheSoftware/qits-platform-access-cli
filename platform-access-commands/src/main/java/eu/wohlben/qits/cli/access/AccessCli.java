package eu.wohlben.qits.cli.access;

import eu.wohlben.qits.cli.access.artifacts.ArtifactsCommand;
import eu.wohlben.qits.cli.access.changelog.ChangelogGroup;
import eu.wohlben.qits.cli.access.checkout.CheckoutDaemonCommand;
import eu.wohlben.qits.cli.access.ci.CiCommand;
import eu.wohlben.qits.cli.access.maintenance.MaintenanceCommand;
import eu.wohlben.qits.cli.access.daemon.SessionDaemonCommand;
import eu.wohlben.qits.cli.access.database.DatabaseCommand;
import eu.wohlben.qits.cli.access.events.EventsCommand;
import eu.wohlben.qits.cli.access.git.GitCredentialCommand;
import eu.wohlben.qits.cli.access.git.GitLoginCommand;
import eu.wohlben.qits.cli.access.help.HelpCommand;
import eu.wohlben.qits.cli.access.login.LoginCommand;
import eu.wohlben.qits.cli.access.mcp.McpCredentialCommand;
import eu.wohlben.qits.cli.access.observe.ObserveCommand;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.projects.ProjectsCommand;
import eu.wohlben.qits.cli.access.projects.ReleaseRequestCommand;
import eu.wohlben.qits.cli.access.projects.RepositoriesCommand;
import eu.wohlben.qits.cli.access.projects.WorkCommand;
import picocli.CommandLine;

/**
 * The {@code qits} command. With no command named, it shows what there is.
 * <p>
 * The help texts of all commands are the one source of SKILL.md ({@code qits help skill}). The
 * first description line here is its trigger, and the footer is its platform rules.
 * <p>
 * The tree here is every command but {@code qits tui}, which needs a terminal and lives with the
 * binary. The binary names this class as its top command ({@code quarkus.picocli.top-command}) and
 * puts {@code tui} back in its place, before {@code help} ({@code QitsCommandLine}), so the binary's
 * tree and its help are what they were when this was one module.
 */
@CommandLine.Command(
        name = "qits",
        mixinStandardHelpOptions = true,
        subcommands = {LoginCommand.class, SessionDaemonCommand.class, CheckoutDaemonCommand.class, ProjectsCommand.class,
                RepositoriesCommand.class, WorkCommand.class,
                ReleaseRequestCommand.class, CiCommand.class, DatabaseCommand.class, MaintenanceCommand.class, EventsCommand.class,
                ObserveCommand.class, GitLoginCommand.class, GitCredentialCommand.class, McpCredentialCommand.class,
                ArtifactsCommand.class, ChangelogGroup.class,
                HelpCommand.class},
        description = {
                "Use for any work on the qits platform from a terminal: signing in, projects and repositories, "
                        + "work items of every archetype (epics, tickets, features, tasks, campaigns) and their comment "
                        + "threads, release requests, CI runs and their logs, "
                        + "domain events, live telemetry, Git "
                        + "pushes to the platform's git host, and publishing release artifacts from a CI step.",
                "Each command calls the platform through its edge, with the session of `qits login`, except "
                        + "`qits artifacts publish`, which runs in a CI step container with no person and never touches that "
                        + "session. `qits <command> --help` shows a command's options, examples and exit codes.",
                "A workspace is the token home: on a runner node, or an admin or editor workspace placed "
                        + "directly on qits-containers, with QITS_TOKEN set every command sends that token as it "
                        + "is, to the public vhosts https://<app>.qits.<QITS_DOMAIN>. It wins over the session and "
                        + "the commissioned pair, nothing is minted or written to disk, and a 401 means the token "
                        + "was deleted with the workspace's container. QITS_URL_<APP> still overrides an address."},
        footerHeading = "%nPlatform rules:%n",
        footer = {
                "- Sign in once with `qits login`, and keep `qits session-daemon` running so the session stays fresh.",
                "- Release only through a release request: `qits release-request create`, or `join` to add a "
                        + "branch to an open one. A push releases nothing.",
                "- Push only branches under refs/heads/external/, after `qits git-login`. Git gets the token from "
                        + "`qits git-credential`.",
                "- qits never prints a token. The two exceptions are `qits git-credential get`, which Git runs, "
                        + "and `qits mcp-credential`, which Claude runs.",
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
