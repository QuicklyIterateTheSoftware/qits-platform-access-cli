package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "maintenance", mixinStandardHelpOptions = true,
        subcommands = {MaintenanceCommand.ScreenshotBaselinesCommand.class, MaintenanceCommand.BumpCommand.class},
        description = {"Jobs of qits-maintenance: screenshot-baselines renders a release request's screenshot "
                        + "references in the CI image and joins them to the request, and bump shows how one job "
                        + "went."},
        footerHeading = "%nNotes:%n",
        footer = {"- --project, --repository, --output and the two -url options may come before or after the command."})
public class MaintenanceCommand implements Runnable {

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @Completes(RepositorySource.class)
    @CommandLine.Option(names = "--repository", paramLabel = "<repository>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository: its id or name.")
    String repository;

    @CommandLine.Option(names = "--maintenance-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The maintenance service's base URL, without /maintenance. Default: QITS_MAINTENANCE_URL, "
                    + "else the session's idp address with `idp` swapped for `maintenance`.")
    String maintenanceUrl;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects, where --project, --repository and "
                    + "--request are looked up. Default: QITS_PROJECTS_URL, else derived like --maintenance-url.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): one line per field. json: the service's answer, pretty-printed.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    private MaintenanceApi maintenance(CliContext context) throws CliFailure, InterruptedException {
        PlatformClient client = new PlatformClient(context.credential());
        return new MaintenanceApi(client, PlatformUrls.maintenance(maintenanceUrl, context.env(), context.idpUrl()));
    }

    private boolean json() throws CliFailure {
        if (output == null || output.isBlank() || output.equalsIgnoreCase("table")) {
            return false;
        }
        if (output.equalsIgnoreCase("json")) {
            return true;
        }
        throw new CliFailure("--output is table or json, not '" + output + "'.", CliFailure.USAGE);
    }

    private static String required(String value, String message) throws CliFailure {
        if (value == null || value.isBlank()) {
            throw new CliFailure(message, CliFailure.USAGE);
        }
        return value.strip();
    }

    /** The bump, one field per line. */
    static void printBump(PrintStream out, JsonNode bump) {
        for (String field : List.of("id", "mode", "repository", "branch", "status", "message", "resultSha",
                "releaseRequestId", "ciRunId", "startedAt", "finishedAt")) {
            String value = text(bump, field);
            if (!value.isEmpty()) {
                out.printf("%-17s %s%n", field, SafeText.line(value));
            }
        }
    }

    @CommandLine.Command(name = "screenshot-baselines", mixinStandardHelpOptions = true,
            description = {"Render a release request's screenshot tests in the CI image, and join the reference "
                    + "images that changed to the request.",
                    "The job starts from the request's fold (release/<request id>), runs "
                            + "`UPDATE_SNAPSHOT=all npm run test:browser`, commits only __screenshots__/ files onto "
                            + "maintenance/baselines/<request id> and joins that branch to the request. Missing "
                            + "references are written too, so a repository's first baselines come from here. It "
                            + "prints the job's id; `qits maintenance bump <id>` shows how it went: SUCCEEDED "
                            + "(joined), NOTHING_TO_DO (every image already matched) or FAILED."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits maintenance --project qits --repository qits-landing-app screenshot-baselines "
                            + "--request 4f2a91c0",
                    "  qits maintenance --project qits --repository qits-landing-app screenshot-baselines "
                            + "--request 4f2a91c0 --work-item qits-112",
                    "",
                    "- The request must be open. Run this after the screenshots changed on purpose; the request's "
                            + "gate compares against the references and fails on a difference.",
                    "- --work-item is the commit subject's scope (chore(<work item>): update screenshot baselines). "
                            + "Without it, the newest one named on the request's own commits is used."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class ScreenshotBaselinesCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MaintenanceCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The release request: its id, or enough of its start to name one "
                        + "(`qits release-request list` shows 8 characters).")
        String request;

        @CommandLine.Option(names = "--work-item", paramLabel = "<id>",
                description = "The work item the commit names, for example qits-112.")
        String workItem;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String wantedProject = required(parent.project, "Name the project: --project <id, slug or name>.");
            String wantedRepository = required(parent.repository, "Name the repository: --repository <id or name>.");
            String wantedRequest = required(request, "Name the release request: --request <id>.");
            ProjectsApi projects = ProjectsApi.connect(context, parent.projectsUrl);
            JsonNode foundProject = projects.project(wantedProject);
            JsonNode repo = projects.repository(foundProject, wantedRepository);
            String requestId = requestId(projects, text(repo, "id"), text(repo, "name"), wantedRequest);

            JsonNode answer = parent.maintenance(context).screenshotBaselines(text(repo, "name"), requestId, workItem);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            String id = text(answer, "id");
            context.out().println("Requested screenshot baselines for release request " + requestId + ": job " + id);
            context.out().println("Follow it with `qits maintenance bump " + id + "`.");
            return 0;
        }

        /** The repository's request whose id starts with {@code wanted}, of any state. */
        private static String requestId(ProjectsApi projects, String repoId, String repoName, String wanted)
                throws CliFailure, InterruptedException {
            String start = wanted.toLowerCase(Locale.ROOT);
            List<JsonNode> matches = new ArrayList<>();
            projects.releaseRequests(repoId, "all").path("requests").forEach(r -> {
                if (text(r, "id").toLowerCase(Locale.ROOT).startsWith(start)) {
                    matches.add(r);
                }
            });
            if (matches.isEmpty()) {
                throw new CliFailure("Repository " + repoName + " has no release request whose id starts with '"
                        + wanted + "'.", CliFailure.USAGE);
            }
            if (matches.size() > 1) {
                throw new CliFailure("'" + wanted + "' is the start of more than one release request of " + repoName
                        + ": " + matches.stream().map(r -> text(r, "id")).collect(Collectors.joining(", "))
                        + ". Give more of the id.", CliFailure.USAGE);
            }
            return text(matches.getFirst(), "id");
        }
    }

    @CommandLine.Command(name = "bump", mixinStandardHelpOptions = true,
            description = {"Show one qits-maintenance job: its mode, status, the sentence about how it went, and the "
                    + "commit it left."},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits maintenance bump 6f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
                    "",
                    "- Run it again to follow a job: REQUESTED and RUNNING are not finished."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class BumpCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        MaintenanceCommand parent;

        @CommandLine.Parameters(index = "0", paramLabel = "<id>", description = "The job's id, as the request printed it.")
        String id;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode bump = parent.maintenance(context).bump(required(id, "Name the job: qits maintenance bump <id>."));
            if (json) {
                ProjectsApi.printJson(context.out(), bump);
                return 0;
            }
            printBump(context.out(), bump);
            return 0;
        }
    }
}
