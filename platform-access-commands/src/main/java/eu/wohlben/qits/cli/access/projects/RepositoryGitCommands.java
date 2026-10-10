package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * The git reads of {@code qits repositories}: branches, commits, a commit's files and one file's
 * diff, and how a branch names its commit subjects. Each names the repository by id or name inside
 * {@code --project}, as the release-request commands do. Commit messages and paths are written by
 * people and agents, so a table shows them through {@link SafeText}, and the JSON form goes through
 * {@link SafeJson}.
 */
final class RepositoryGitCommands {

    private RepositoryGitCommands() {
    }

    /** The options every git read takes besides the inherited --project. */
    abstract static class GitRead extends PlatformCommand {

        @CommandLine.ParentCommand
        RepositoriesCommand parent;

        @Completes(RepositorySource.class)
        @CommandLine.Option(names = "--repository", paramLabel = "<repository>",
                description = "The repository (required): its id or name.")
        String repository;

        /** The service, and the repository's id. */
        record Target(ProjectsApi api, String repoId) {
        }

        Target target(CliContext context) throws CliFailure, InterruptedException {
            String wantedProject = RepositoriesCommand.required(parent.project, RepositoriesCommand.NAME_THE_PROJECT);
            String wantedRepository = RepositoriesCommand.required(repository,
                    "Name the repository: --repository <id or name>.");
            ProjectsApi api = ProjectsApi.connect(context, parent.options.projectsUrl);
            JsonNode repo = api.repository(api.project(wantedProject), wantedRepository);
            return new Target(api, text(repo, "id"));
        }

        boolean json() throws CliFailure {
            return json(parent.options.output);
        }
    }

    static String cell(String value, int max) {
        return Table.cell(SafeText.line(value), max);
    }

    @CommandLine.Command(name = "branches", mixinStandardHelpOptions = true,
            description = {"List a repository's branches: each with its parent, how far it is ahead of and behind "
                    + "that parent, and whether it can be cleaned up (fully merged, nothing depends on it).",
                    "Reads GET /projects/api/repositories/{id}/branches."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits branches --repository qits-ci-service"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class BranchesCommand extends GitRead {

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            Target t = target(context);
            JsonNode answer = t.api().branches(t.repoId());
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode b : answer.path("branches")) {
                rows.add(List.of(cell(text(b, "name"), 80), cell(text(b, "parent"), 60), cell(text(b, "ahead"), 6),
                        cell(text(b, "behind"), 6), b.path("canCleanup").asBoolean() ? "yes" : "-"));
            }
            if (rows.isEmpty()) {
                context.out().println("The repository has no branches.");
                return 0;
            }
            Table.print(context.out(), "", List.of("BRANCH", "PARENT", "AHEAD", "BEHIND", "CLEANUP"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "commits", mixinStandardHelpOptions = true,
            description = {"List the commits on a branch that are not on its parent, newest first, each with the "
                    + "files it changed.",
                    "Reads GET /projects/api/repositories/{id}/commits?branch=<branch>. The table shows hash, date, "
                            + "author, file count and subject; -o json adds the full message, the files and the "
                            + "parents."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits commits --repository qits-ci-service --branch ticket/x"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class CommitsCommand extends GitRead {

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>",
                description = "The branch whose commits to list (required).")
        String branch;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String wanted = RepositoriesCommand.required(branch, "Name the branch: --branch <name>.");
            Target t = target(context);
            JsonNode answer = t.api().commits(t.repoId(), wanted);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode c : answer.path("commits")) {
                rows.add(List.of(cell(text(c, "shortHash"), 12), cell(text(c, "date"), 25), cell(text(c, "author"), 30),
                        String.valueOf(c.path("files").size()), cell(text(c, "message").lines().findFirst().orElse(""), 80)));
            }
            if (rows.isEmpty()) {
                context.out().println("The branch has no commits of its own.");
                return 0;
            }
            Table.print(context.out(), "", List.of("COMMIT", "DATE", "AUTHOR", "FILES", "SUBJECT"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "commit-changes", mixinStandardHelpOptions = true,
            description = {"List the files one commit changed against its first parent, or against --parent: "
                    + "path, old path for a rename, and change type.",
                    "Reads GET /projects/api/repositories/{id}/commits/{hash}/changes. Use commit-diff for one "
                            + "file's diff."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits commit-changes --repository qits-ci-service --commit 1a2b3c4"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class CommitChangesCommand extends GitRead {

        @CommandLine.Option(names = "--commit", paramLabel = "<hash>",
                description = "The commit (required): its full or short hash.")
        String commit;

        @CommandLine.Option(names = "--parent", paramLabel = "<hash>",
                description = "The commit to compare against. Default: the commit's first parent.")
        String parentCommit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String hash = RepositoriesCommand.required(commit, "Name the commit: --commit <hash>.");
            Target t = target(context);
            JsonNode answer = t.api().commitChanges(t.repoId(), hash, parentCommit);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode f : answer.path("files")) {
                rows.add(List.of(cell(text(f, "changeType"), 10), cell(text(f, "path"), 120),
                        cell(text(f, "oldPath"), 120)));
            }
            if (rows.isEmpty()) {
                context.out().println("The commit changed no files.");
                return 0;
            }
            Table.print(context.out(), "", List.of("CHANGE", "PATH", "OLD PATH"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "commit-diff", mixinStandardHelpOptions = true,
            description = {"Print the unified diff of one file in one commit, against the same base as "
                    + "commit-changes. A binary file or a pure rename has an empty diff.",
                    "Reads GET /projects/api/repositories/{id}/commits/{hash}/diff?path=<path>."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits commit-diff --repository qits-ci-service --commit 1a2b3c4 "
                    + "--path README.md"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class CommitDiffCommand extends GitRead {

        @CommandLine.Option(names = "--commit", paramLabel = "<hash>",
                description = "The commit (required): its full or short hash.")
        String commit;

        @CommandLine.Option(names = "--path", paramLabel = "<path>",
                description = "The file (required): its path in the repository.")
        String path;

        @CommandLine.Option(names = "--parent", paramLabel = "<hash>",
                description = "The commit to compare against. Default: the commit's first parent.")
        String parentCommit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            String hash = RepositoriesCommand.required(commit, "Name the commit: --commit <hash>.");
            String file = RepositoriesCommand.required(path, "Name the file: --path <path>.");
            Target t = target(context);
            JsonNode answer = t.api().commitDiff(t.repoId(), hash, file, parentCommit);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println(SafeText.line(text(answer, "changeType")) + " " + SafeText.line(text(answer, "path")));
            WorkEntities.lines(context.out(), "", text(answer, "diff"));
            return 0;
        }
    }

    @CommandLine.Command(name = "commit-subjects", mixinStandardHelpOptions = true,
            description = {"Count how many of a branch's newest commits name their subject as term(<project>-<n>): "
                    + "message, for example feat(qits-1337): add the export, and list the ones that do not.",
                    "Reads GET /projects/api/repositories/{id}/commit-subjects. Each commit is EXEMPT (a merge, or "
                            + "a platform machine author such as a bump or release commit), COMPLYING or "
                            + "NON_COMPLYING. guard says whether the branch opts into the git host's subject guard "
                            + "(.config/qits/commit-subjects.yml holding enforce: true)."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits repositories --project qits commit-subjects --repository qits-ci-service",
                    "  qits repositories --project qits commit-subjects --repository qits-ci-service --branch ticket/x "
                            + "--limit 20"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class CommitSubjectsCommand extends GitRead {

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>",
                description = "The branch to read. Default: the repository's main branch.")
        String branch;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "How many of the newest commits to read. Default 100, at most 1000.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json();
            Target t = target(context);
            JsonNode answer = t.api().commitSubjects(t.repoId(), branch, limit);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            JsonNode counts = answer.path("counts");
            context.out().println("branch " + SafeText.line(text(answer, "branch")) + ": "
                    + counts.path("complying").asInt() + " complying, " + counts.path("nonComplying").asInt()
                    + " not, " + counts.path("exempt").asInt() + " exempt, of " + counts.path("total").asInt()
                    + "; guard " + (answer.path("guardEnabled").asBoolean() ? "on" : "off"));
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode c : answer.path("nonComplying")) {
                rows.add(List.of(cell(text(c, "shortHash"), 12), cell(text(c, "authorName"), 30),
                        cell(text(c, "subject"), 100)));
            }
            if (!rows.isEmpty()) {
                context.out().println();
                Table.print(context.out(), "", List.of("COMMIT", "AUTHOR", "SUBJECT"), rows);
            }
            return 0;
        }
    }
}
