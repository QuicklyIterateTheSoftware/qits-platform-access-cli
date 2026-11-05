package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "repositories", mixinStandardHelpOptions = true,
        subcommands = {RepositoriesCommand.ListCommand.class, RepositoriesCommand.CreateCommand.class,
                RepositoriesCommand.ArchiveCommand.class, RepositoriesCommand.UnarchiveCommand.class,
                RepositoryGitCommands.BranchesCommand.class, RepositoryGitCommands.CommitsCommand.class,
                RepositoryGitCommands.CommitChangesCommand.class, RepositoryGitCommands.CommitDiffCommand.class,
                RepositoryGitCommands.CommitSubjectsCommand.class, RemoteLoginCommand.class,
                RepositoriesCommand.BackupCommand.class},
        description = {"The repositories of one project: list shows them, create stands a new one up. Release "
                + "requests take a repository's id or name as --repository.",
                "branches, commits, commit-changes, commit-diff and commit-subjects read one repository's git "
                        + "history; each takes the repository as --repository <id or name>.",
                "archive and unarchive ask for repository.archived in the repository's "
                        + ".config/qits/repository.yml, released through its own release request.",
                "remote-login attaches this terminal to a repository's backup-remote sign-in, where git asks for "
                        + "the credentials the project's backups push with.",
                "backup reset makes a repository's backup twin ours when its main shares no history with ours "
                        + "(a forge's seed commit).",
                "A repository's name says the role it plays to a reader, but it decides nothing (owner ruling "
                        + "2026-11-03): state the kind with create's --archetype, or declare it in the "
                        + "repository's own .config/qits/repository.yml (repository.archetype), which the wrapper "
                        + "reconcile applies. With neither, the repository simply has no archetype yet."})
public class RepositoriesCommand implements Runnable {

    @CommandLine.Mixin
    ProjectsOptions options;

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    static final String NAME_THE_PROJECT = "Name the project: --project <id, slug or name>.";

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = "List the project's repositories: name, archetype, component and id. --project may come "
                    + "before or after list.",
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits list", "  qits repositories list --project qits -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = required(parent.project, NAME_THE_PROJECT);
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode project = api.project(wanted);
            JsonNode answer = api.repositories(text(project, "id"));
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> repositories = ProjectsApi.entries(answer, "repository");
            if (repositories.isEmpty()) {
                context.out().println("Project " + ProjectsApi.projectLabel(project) + " has no repositories.");
                return 0;
            }
            Table.print(context.out(), "", List.of("NAME", "ARCHETYPE", "COMPONENT", "ID"), repositories.stream()
                    .map(r -> List.of(Table.cell(text(r, "name"), 60), Table.cell(text(r, "archetype"), 30),
                            Table.cell(text(r, "component"), 40), Table.cell(text(r, "id"), 64)))
                    .toList());
            return 0;
        }
    }

    /**
     * Stands a repository up: a blank one on the platform's git host, and the entry that mounts it
     * in the project's wrapper.
     *
     * <p><b>--archetype states the kind; the name never did (owner ruling 2026-11-03).</b> The name's
     * role suffix is a convention for a reader, not an input any service reads. A caller states the
     * kind with this flag, or leaves it to the repository's own {@code .config/qits/repository.yml}
     * once a wrapper reconcile applies it; with neither, the row is created with no archetype at
     * all. The archetype is still printed, because it is what the door answered, never what this
     * command derived.
     */
    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Create a repository in the project: a blank one on the platform's git host, seeded "
                    + "with the repository template, and mounted in the project's wrapper.",
                    "The NAME says what kind of component it is to a reader; it decides nothing. State the kind "
                            + "with --archetype, or leave it to the repository's own "
                            + ".config/qits/repository.yml (repository.archetype), which a wrapper reconcile "
                            + "applies. With neither, the repository has no archetype until one is declared. "
                            + "Name the repository <component>[-<modifier>]-<role> all the same, so a reader "
                            + "still sees the role.",
                    "--component places the wrapper entry at components/<component>/<name>. Without it the "
                            + "wrapper's own layout decides."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits repositories --project qits create qits-docs-app --component qits-docs --archetype app",
                    "  qits repositories create qits-payments-service --project qits --component qits-payments "
                            + "--archetype service",
                    "  qits repositories --project qits create qits-docs-app -o json",
                    "",
                    "- --archetype: SERVICE, DAEMON, LIBRARY, FRONTEND, APP, CLI or IMAGE, any case. Anything "
                            + "else is left for the service to refuse.",
                    "- Creating a repository needs the role qits:admin or qits:agent.",
                    "- The command prints what the service answered: the name, the archetype (if any was "
                            + "declared), the component, the id, and the wrapper path the entry was written at."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The repository exists and the wrapper names it.",
                    "1:The platform refused (the message names the status and what to do next), or cannot be "
                            + "reached.",
                    HelpText.USAGE})
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<name>",
                description = "The repository's name. Its role suffix tells a reader what kind of component it "
                        + "is, but decides nothing: state the kind with --archetype. It is also what "
                        + "../<name>.git resolves to.")
        String name;

        @CommandLine.Option(names = "--component", paramLabel = "<component>",
                description = "The technical component to mount the entry under: components/<component>/<name>. "
                        + "Default: the wrapper's own layout decides.")
        String component;

        @CommandLine.Option(names = "--archetype", paramLabel = "<archetype>",
                description = "The kind of component this is: SERVICE, DAEMON, LIBRARY, FRONTEND, APP, CLI or "
                        + "IMAGE, any case. Default: none: the repository has no archetype until this, or its "
                        + ".config/qits/repository.yml, declares one.")
        String archetype;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wanted = required(parent.project, NAME_THE_PROJECT);
            String repository = required(name, "Name the repository to create: `qits repositories --project "
                    + "<project> create <name>`.");
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode project = api.project(wanted);
            JsonNode answer;
            try {
                answer = api.createRepository(text(project, "id"), repository, component, archetype);
            } catch (CliFailure refused) {
                throw explain(refused, repository);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            print(context, answer);
            return 0;
        }

        /**
         * What the service answered, the archetype first among equals: it is what the row was
         * declared as, through --archetype or the repository's .config/qits/repository.yml, not
         * what this command guessed from the name. A repository declared as neither prints a dash,
         * the same as any other empty cell (Table.cell). The wrapper path is the proof the wrapper
         * entry was written; the service leaves it out when there is none, and then so does this.
         */
        private static void print(CliContext context, JsonNode answer) {
            JsonNode repository = answer.path("repository");
            context.out().println("Repository " + text(repository, "name"));
            List<List<String>> rows = new ArrayList<>(List.of(
                    List.of("archetype", Table.cell(text(repository, "archetype"), 30)),
                    List.of("component", Table.cell(text(repository, "component"), 40)),
                    List.of("id", Table.cell(text(repository, "id"), 64))));
            String wrapperPath = text(answer, "wrapperPath");
            if (!wrapperPath.isEmpty()) {
                rows.add(List.of("wrapper entry", Table.cell(wrapperPath, 200)));
            }
            Table.print(context.out(), "  ", null, rows);
        }

        /**
         * The one refusal this command meets in the ordinary course of standing a repository up,
         * with the step that follows it. It is the platform's own answer, so it keeps the refusal's
         * exit code; what this adds is the sentence that says what to do next.
         */
        private static CliFailure explain(CliFailure refused, String repository) {
            String said = refused.getMessage() == null ? "" : refused.getMessage();
            if (refused.status() == 403) {
                return new CliFailure(said + System.lineSeparator()
                        + "Creating a repository needs qits:admin or qits:agent. A 403 on an agent credential "
                        + "means the service has not been released with that role on this door yet.",
                        CliFailure.FAILED);
            }
            return refused;
        }
    }

    /**
     * Shared by {@link ArchiveCommand} and {@link UnarchiveCommand}: the {@code --repository}
     * option and the resolve-then-call-then-print shape, which is the whole of each command but
     * for which door it calls.
     */
    abstract static class ArchiveAction extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @Completes(RepositorySource.class)
        @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                description = "The repository (required): its id or name.")
        String repository;

        abstract JsonNode call(ProjectsApi api, String repoId) throws CliFailure, InterruptedException;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            String wantedProject = required(parent.project, NAME_THE_PROJECT);
            String wantedRepository = required(repository, "Name the repository: --repository <id or name>.");
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode repo = api.repository(api.project(wantedProject), wantedRepository);
            JsonNode answer = call(api, text(repo, "id"));
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            print(context, answer);
            return 0;
        }

        /** outcome, archived, the branch and release request it opened (if any), and the detail. */
        private static void print(CliContext context, JsonNode answer) {
            List<List<String>> rows = new ArrayList<>(List.of(
                    List.of("outcome", Table.cell(text(answer, "outcome"), 20)),
                    List.of("archived", answer.path("archived").asBoolean() ? "yes" : "no")));
            String branch = text(answer, "branch");
            if (!branch.isEmpty()) {
                rows.add(List.of("branch", Table.cell(branch, 120)));
            }
            String qualifiedId = text(answer, "qualifiedId");
            if (!qualifiedId.isEmpty()) {
                rows.add(List.of("release request", Table.cell(qualifiedId, 60)));
            }
            String detail = text(answer, "detail");
            if (!detail.isEmpty()) {
                rows.add(List.of("detail", Table.cell(detail, 200)));
            }
            Table.print(context.out(), "  ", null, rows);
        }
    }

    /**
     * Asks for {@code repository.archived: true} in the repository's own {@code
     * .config/qits/repository.yml}, as a commit on {@code archive/<name>} released through the
     * repository's release request. Never a push to main: see {@link ProjectsApi#archiveRepository}.
     */
    @CommandLine.Command(name = "archive", mixinStandardHelpOptions = true,
            description = {"Archive a repository: ask for repository.archived: true in its "
                    + ".config/qits/repository.yml, on the branch archive/<repository name> (an absent file is "
                    + "created). Never a push to main.",
                    "Reads the file at main first: when it already says archived: true nothing is opened and the "
                            + "outcome is unchanged (HTTP 200). Otherwise the edit is committed onto archive/<name> "
                            + "(the branch is created at main, or brought up to date with it) and the branch is "
                            + "asked for through POST .../release-requests, which creates or joins the repository's "
                            + "open request (HTTP 202, outcome requested).",
                    "qits-maintenance stops bumping an archived repository's dependencies unless its "
                            + "maintenance.yml sets dependency-bumps: true.",
                    "POST /projects/api/repositories/{id}/archive."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits archive --repository qits-ci-service",
                    "",
                    "- Needs qits:admin, qits:admin-agent, qits:desk-admin, or qits:desk bound to its own project; "
                            + "qits:agent alone is refused (HTTP 403)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The repository already said archived: true (outcome unchanged), or the edit landed "
                    + "and a release request was asked for (outcome requested).",
                    "1:The platform refused (for example a conflicting branch or a repository with no name, HTTP "
                            + "409, or the file cannot be edited safely, HTTP 422), or cannot be reached (HTTP 502 "
                            + "or 503).",
                    HelpText.USAGE})
    public static class ArchiveCommand extends ArchiveAction {

        @Override
        JsonNode call(ProjectsApi api, String repoId) throws CliFailure, InterruptedException {
            return api.archiveRepository(repoId);
        }
    }

    /**
     * {@link ArchiveCommand}'s mirror: removes {@code repository.archived} the same way. See
     * {@link ProjectsApi#unarchiveRepository}.
     */
    @CommandLine.Command(name = "unarchive", mixinStandardHelpOptions = true,
            description = {"Take a repository out of the archive: ask for repository.archived to be removed from "
                    + "its .config/qits/repository.yml, on the branch unarchive/<repository name>.",
                    "Reads the file at main first: when it does not say archived: true nothing is opened and the "
                            + "outcome is unchanged (HTTP 200); a file that is absent is already unarchived. "
                            + "Otherwise the edit is committed onto unarchive/<name> and the branch is asked for "
                            + "through POST .../release-requests, which creates or joins the repository's open "
                            + "request (HTTP 202, outcome requested).",
                    "POST /projects/api/repositories/{id}/unarchive."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits unarchive --repository qits-ci-service",
                    "",
                    "- Needs qits:admin, qits:admin-agent, qits:desk-admin, or qits:desk bound to its own project; "
                            + "qits:agent alone is refused (HTTP 403)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The repository already did not say archived: true (outcome unchanged), or the edit "
                    + "landed and a release request was asked for (outcome requested).",
                    "1:The platform refused (for example a conflicting branch or a repository with no name, HTTP "
                            + "409, or the file cannot be edited safely, HTTP 422), or cannot be reached (HTTP 502 "
                            + "or 503).",
                    HelpText.USAGE})
    public static class UnarchiveCommand extends ArchiveAction {

        @Override
        JsonNode call(ProjectsApi api, String repoId) throws CliFailure, InterruptedException {
            return api.unarchiveRepository(repoId);
        }
    }

    /**
     * {@code qits repositories backup}: what a person does about a repository's backup twin, the
     * copy on the forge the project's backups push to. Only {@code reset} so far (qits-1885).
     */
    @CommandLine.Command(name = "backup", mixinStandardHelpOptions = true,
            subcommands = {RepositoriesCommand.BackupCommand.ResetCommand.class},
            description = {"A repository's backup twin, the copy on the forge the project's backups push to: reset "
                    + "makes the twin's main ours when it shares no history with ours (a forge's seed commit)."})
    public static class BackupCommand implements Runnable {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }

        /**
         * Makes the backup twin's main ours (qits-1885): the fix for a twin the forge seeded with a
         * commit of its own, which every ordinary backup push is refused on. The service probes the
         * twin and decides; this command only names the repository and the reason, and prints what
         * was decided. See {@link ProjectsApi#backupReset}.
         */
        @CommandLine.Command(name = "reset", mixinStandardHelpOptions = true,
                description = {"Reset a repository's backup twin whose main shares no history with ours, such "
                        + "as a forge's seed commit (a README the forge created): ordinary backups are refused "
                        + "on such a twin, and this makes its main ours.",
                        "The service probes the twin's main and decides. A twin with no main, at ours or behind "
                                + "ours is fast-forwarded, with no force (kind FAST_FORWARD). A twin whose main "
                                + "shares no commit with ours has its main replaced by ours (kind "
                                + "UNRELATED_RESET); only main is ever forced. A twin that is ahead of ours or "
                                + "diverged from a common base holds work the platform does not have: it is "
                                + "refused (HTTP 409, naming both shas) and nothing is pushed.",
                        "The reset is recorded with who decided it and --reason.",
                        "POST /projects/api/repositories/{id}/backup-reset."},
                footerHeading = HelpText.EXAMPLES,
                footer = {"  qits repositories --project qits backup reset --repository qits-ci-service "
                        + "--reason \"The forge seeded the twin with a README commit.\"",
                        "  qits repositories backup reset --project qits --repository qits-ci-service "
                                + "--reason \"Seed commit\" -o json",
                        "",
                        "- Only a person (qits:admin, signed in as a person) or the project's own delivery desk "
                                + "may reset a twin: the decision is that the forge's copy of main may be "
                                + "discarded. Agents, CI and another project's desk are refused (HTTP 403).",
                        "- The table prints the repository, the kind, the twin's main before and after, who "
                                + "decided and the reason. -o json prints the service's answer."},
                exitCodeListHeading = HelpText.EXIT_CODES,
                exitCodeList = {"0:The twin's main is ours now: fast-forwarded, or reset from unrelated history.",
                        "1:The platform refused (for example your roles, HTTP 403, a repository it does not know, "
                                + "HTTP 404, or a twin that is ahead, diverged, moved meanwhile or missing, HTTP "
                                + "409), or cannot be reached.",
                        HelpText.USAGE})
        public static class ResetCommand extends PlatformCommand {

            @CommandLine.ParentCommand
            BackupCommand backup;

            @Completes(RepositorySource.class)
            @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                    description = "The repository (required): its id or name.")
            String repository;

            @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                    description = "Why the twin's main may be replaced (required). It is recorded with the "
                            + "reset.")
            String reason;

            @Override
            protected int execute(CliContext context) throws CliFailure, InterruptedException {
                RepositoriesCommand parent = backup.parent;
                boolean json = json(parent.options.output);
                String wantedProject = required(parent.project, NAME_THE_PROJECT);
                String wantedRepository = required(repository, "Name the repository: --repository <id or name>.");
                String why = required(reason, "Say why the twin's main may be replaced: --reason <text>.");
                ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
                JsonNode repo = api.repository(api.project(wantedProject), wantedRepository);
                JsonNode answer = api.backupReset(text(repo, "id"), why);
                if (json) {
                    ProjectsApi.printJson(context.out(), answer);
                    return 0;
                }
                print(context, text(repo, "name").isEmpty() ? wantedRepository : text(repo, "name"), answer);
                return 0;
            }

            /** repository, kind, the twin's main before and after, who decided, and why. */
            private static void print(CliContext context, String name, JsonNode answer) {
                String before = text(answer, "twinShaBefore");
                Table.print(context.out(), "  ", null, List.of(
                        List.of("repository", Table.cell(name, 120)),
                        List.of("kind", Table.cell(text(answer, "kind"), 30)),
                        List.of("twin main", Table.cell((before.isEmpty() ? "(none)" : before) + " -> "
                                + text(answer, "pushedSha"), 100)),
                        List.of("decided by", Table.cell(text(answer, "decidedBy"), 120)),
                        List.of("reason", Table.cell(text(answer, "reason"), 200))));
            }
        }
    }
}
