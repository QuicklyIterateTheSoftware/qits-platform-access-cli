package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.terminal.LocalTerminal;
import eu.wohlben.qits.cli.access.terminal.TerminalAttach;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits repositories remote-login}: this terminal attached to a repository's backup-remote
 * sign-in on qits-projects, {@code WS /projects/api/repositories/{id}/remote-login}, the projects
 * page's "Sign in to backup remote" pane. The server runs an interactive {@code git push} whose
 * prompt fills the shared credential store the backups push with; the socket speaks the workspace
 * terminal's wire, so the attachment is {@link TerminalAttach}'s.
 * <p>
 * <b>The default is the project repository</b>, the {@code wrapper.repositoryId} of the project's
 * repository list, as the page's backup panel picks it: every project has one, and what the sign-in
 * authenticates is the host, not the repository.
 * <p>
 * <b>After the terminal closes</b> it does what the panel does: a close says the terminal ended, not
 * that the sign-in worked, so it asks for every backup of the project to run again, waits the
 * panel's one {@link #REFRESH_DELAY}, and prints each repository's last backup as it then reads.
 */
@CommandLine.Command(name = "remote-login", mixinStandardHelpOptions = true,
        description = {"Attach this terminal to a repository's backup-remote sign-in: the server runs an "
                + "interactive git push to the backup remote, and git's username and password prompts come here. "
                + "What you answer fills the credential store every backup of the project pushes with, so one "
                + "sign-in fixes every repository whose last backup says AUTH_REQUIRED.",
                "Without --repository it is the project repository (the one whose .gitmodules names the others), "
                        + "as the project page's Backups panel uses: what the sign-in authenticates is the host, "
                        + "not the repository. The repository needs a backup remote.",
                "Ctrl-] detaches; the sign-in waits about a minute for you to attach again (run the same "
                        + "command), and the server replays what the terminal held. Every other key goes to git. "
                        + "A dropped connection attaches again after 0.5, 1, 2, 4 and 4 s.",
                "When the sign-in ends (git exits, or the server refuses in the terminal, for example because a "
                        + "push is already running), it asks for every backup of the project to run again (POST "
                        + "/projects/api/projects/{id}/repositories/backup-sync), waits 3 s and prints each "
                        + "repository's last backup. The pushes run in the background, so a result still says "
                        + "AUTH_REQUIRED until its push has run: ask again with `qits repositories list -o json`.",
                "With stdin not a terminal it only streams what the terminal prints: nothing is typed.",
                "WS /projects/api/repositories/{id}/remote-login. Needs qits:admin or qits:admin-agent; "
                        + "qits:agent alone is refused, and so is qits:desk-admin: the socket runs an interactive "
                        + "push to the forge, and the delivery desk never pushes (epic qits-1621)."},
        footerHeading = HelpText.EXAMPLES,
        footer = {"  qits repositories remote-login --project qits",
                "  qits repositories remote-login --project qits --repository qits-ci-service",
                "",
                "- git asks for a username and a password: give a scoped personal access token, not the "
                        + "account's password. Nothing is echoed at the password prompt.",
                "- Not an MCP tool: it needs a terminal and runs until the sign-in ends."},
        exitCodeListHeading = HelpText.EXIT_CODES,
        exitCodeList = {"0:The sign-in ended and the backups were asked to run again, you detached (Ctrl-]), or it "
                + "was stopped (SIGINT, SIGTERM). A 0 does not say the sign-in worked: the backups say that.",
                "1:The platform refused (the socket or the backups), the repository has no backup remote, or the "
                        + "connection dropped and five attempts to attach again failed.",
                HelpText.USAGE})
@TuiCommand(interaction = Interaction.TERMINAL)
public class RemoteLoginCommand extends PlatformCommand {

    /** The backup panel's {@code BACKUP_REFRESH_DELAY_MS}: one read after the sync, not a poll. */
    static final Duration REFRESH_DELAY = Duration.ofSeconds(3);

    @CommandLine.ParentCommand
    RepositoriesCommand parent;

    @Completes(RepositorySource.class)
    @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
            description = "The repository whose backup remote to sign in to: its id or name. Default: the "
                    + "project repository.")
    String repository;

    /** The caller's terminal; a test hands its own. */
    LocalTerminal.Factory terminals = LocalTerminal::of;

    @Override
    protected int execute(CliContext context) throws CliFailure, InterruptedException {
        if (CliContext.inMcpService()) {
            throw new CliFailure("The sign-in needs a terminal, and the MCP service has none: run `qits repositories "
                    + "remote-login` in a terminal.", CliFailure.USAGE);
        }
        String wanted = RepositoriesCommand.required(parent.project, RepositoriesCommand.NAME_THE_PROJECT);
        ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
        JsonNode project = api.project(wanted);
        JsonNode repo = repository == null || repository.isBlank()
                ? projectRepository(api, project)
                : api.repository(project, repository.strip());
        String name = text(repo, "name").isEmpty() ? text(repo, "id") : text(repo, "name");
        if (repo.has("backupUrl") && text(repo, "backupUrl").isEmpty()) {
            throw new CliFailure("Repository " + SafeText.line(name) + " has no backup remote, so there is nothing to "
                    + "sign in to.", CliFailure.FAILED);
        }
        URI uri = api.remoteLoginSocket(text(repo, "id"));
        // Fails at once without a credential, rather than in the reconnect loop.
        context.credential().bearer();
        TerminalAttach attachment = new TerminalAttach(api.client(), uri,
                new TerminalAttach.Subject("the backup-remote sign-in of " + SafeText.line(name), "the sign-in"),
                terminals.open(context), context.out(), context.err(), context.sleeper(), TerminalAttach.BACKOFF,
                TerminalAttach.SIZE_POLL);
        context.onStop().accept(attachment::stop);
        int exit = attachment.run();
        if (!attachment.ended()) {
            if (exit == 0) {
                context.err().println("qits: it waits about a minute for you to attach again: run the same command.");
            }
            return exit;
        }
        return afterTheSignIn(context, api, project);
    }

    /** The project's {@code wrapper.repositoryId}, the repository the backup panel signs in through. */
    private static JsonNode projectRepository(ProjectsApi api, JsonNode project) throws CliFailure, InterruptedException {
        String id = text(api.repositories(text(project, "id")).path("wrapper"), "repositoryId");
        String label = ProjectsApi.projectLabel(project);
        if (id.isEmpty()) {
            throw new CliFailure("Project " + label + " has no project repository. Name the repository to sign in "
                    + "through: --repository <id or name>.", CliFailure.USAGE);
        }
        return api.repository(project, id);
    }

    /**
     * The backup panel's {@code onTerminalClosed}: ask for every backup to run again, then read once,
     * a moment later. A refusal of the ask is the command's failure; the read is only a report.
     */
    private int afterTheSignIn(CliContext context, ProjectsApi api, JsonNode project)
            throws CliFailure, InterruptedException {
        String projectId = text(project, "id");
        String label = ProjectsApi.projectLabel(project);
        JsonNode scheduled = api.backupSyncProject(projectId);
        int count = scheduled.path("scheduled").asInt();
        context.out().println("Asked for the backups of " + count + " repositor" + (count == 1 ? "y" : "ies")
                + " of project " + label + " to run again. A close says the sign-in ended, not that it worked: the "
                + "backups say that.");
        context.sleeper().sleep(REFRESH_DELAY);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode r : ProjectsApi.entries(api.repositories(projectId), "repository")) {
            if (text(r, "backupUrl").isEmpty()) {
                continue;
            }
            JsonNode last = r.path("lastBackup");
            boolean never = last.isMissingNode() || last.isNull();
            rows.add(List.of(Table.cell(SafeText.line(text(r, "name")), 60),
                    never ? "never" : Table.cell(SafeText.line(text(last, "outcome")), 20),
                    never ? "-" : Table.cell(SafeText.line(text(last, "at")), 40),
                    never ? "" : Table.cell(SafeText.line(text(last, "detail")), 80)));
        }
        if (!rows.isEmpty()) {
            Table.print(context.out(), "", List.of("REPOSITORY", "LAST BACKUP", "AT", "DETAIL"), rows);
        }
        context.out().println("The pushes run in the background, so a backup may still read as before. Ask again "
                + "with `qits repositories --project " + label + " list -o json` (each repository's lastBackup).");
        return 0;
    }
}
