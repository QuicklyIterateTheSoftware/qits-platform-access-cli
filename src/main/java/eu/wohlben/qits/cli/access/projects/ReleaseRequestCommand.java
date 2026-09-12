package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "release-request", mixinStandardHelpOptions = true,
        subcommands = {ReleaseRequestCommand.ListCommand.class, ReleaseRequestCommand.CreateCommand.class},
        description = "The release requests of one repository.")
public class ReleaseRequestCommand implements Runnable {

    /** The one state the default list leaves out: the service adds the last 10 of them. */
    static final String RELEASED = "RELEASED";

    @CommandLine.Mixin
    ProjectsOptions options;

    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @CommandLine.Option(names = "--repository", paramLabel = "<repository>", scope = CommandLine.ScopeType.INHERIT,
            description = "The repository: its id or name.")
    String repository;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    /** Checks the names, then finds the repository. */
    private Target target(CliContext context) throws CliFailure, InterruptedException {
        String wantedProject = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
        String wantedRepository = RepositoriesCommand.required(repository, "Name the repository: --repository <id or name>.");
        ProjectsApi api = ProjectsApi.connect(context, options.projectsUrl);
        JsonNode repo = api.repository(api.project(wantedProject), wantedRepository);
        return new Target(api, text(repo, "id"), text(repo, "name"));
    }

    private record Target(ProjectsApi api, String repoId, String repoName) {
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the repository's open release requests.",
                    "Open means every state but RELEASED and WITHDRAWN. --state asks for other ones."})
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE|all>",
                description = "Only this state (PENDING, READY, RELEASED, REJECTED, FAILED, CONFLICTED, "
                        + "WITHDRAWN), or all for every request. Default: the open ones.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            Target target = parent.target(context);
            JsonNode answer = target.api().releaseRequests(target.repoId(), state);
            boolean openOnly = state == null || state.isBlank();
            if (openOnly) {
                answer = withoutReleased(answer);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            List<JsonNode> requests = new ArrayList<>();
            answer.path("requests").forEach(requests::add);
            if (requests.isEmpty()) {
                context.out().println(openOnly
                        ? "No open release requests for " + target.repoName() + "."
                        : "No release requests in state " + state.strip() + " for " + target.repoName() + ".");
                return 0;
            }
            Table.print(context.out(), "", List.of("ID", "STATE", "PRIORITY", "SUMMARY", "VERSION", "UPDATED"),
                    requests.stream().map(r -> List.of(
                            Table.cell(shortId(text(r, "id")), 8),
                            Table.cell(text(r, "state"), 12),
                            Table.cell(text(r, "priority"), 10),
                            Table.cell(text(r, "summary"), 60),
                            Table.cell(text(r, "version"), 24),
                            Table.time(text(r, "updatedAt")))).toList());
            return 0;
        }

        /**
         * The service's default answer holds the open requests and the last 10 released. The list
         * shows open ones, so the released are dropped here, in both output forms.
         */
        static JsonNode withoutReleased(JsonNode answer) {
            ObjectNode copy = answer.isObject() ? ((ObjectNode) answer).deepCopy() : JsonNodeFactory.instance.objectNode();
            ArrayNode kept = copy.arrayNode();
            answer.path("requests").forEach(r -> {
                if (!RELEASED.equalsIgnoreCase(text(r, "state"))) {
                    kept.add(r);
                }
            });
            copy.set("requests", kept);
            return copy;
        }
    }

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Ask for a branch to be released once its builds are green.",
                    "The platform may answer with a new request, the open request that already holds the branch, "
                            + "or (on a project wrapper) the open request the branch joined. It prints what came back."})
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>", required = true,
                description = "The branch to release.")
        String branch;

        @CommandLine.Option(names = "--summary", paramLabel = "<text>", required = true,
                description = "What the release is for, in a sentence.")
        String summary;

        @CommandLine.Option(names = "--priority", paramLabel = "<priority>",
                description = "LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: the platform's (MEDIUM).")
        String priority;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (branch.isBlank() || summary.isBlank()) {
                throw new CliFailure("--branch and --summary must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode answer = target.api().createReleaseRequest(target.repoId(), branch.strip(), summary.strip(), priority);
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }
    }

    static void printRequest(PrintStream out, JsonNode request) {
        out.println("Release request " + text(request, "id"));
        Table.print(out, "  ", null, List.of(
                List.of("repository", Table.cell(text(request, "repoName"), 200)),
                List.of("state", Table.cell(text(request, "state"), 200)),
                List.of("priority", Table.cell(text(request, "priority"), 200)),
                List.of("summary", Table.cell(text(request, "summary"), 200)),
                List.of("requester", Table.cell(text(request, "requester"), 200)),
                List.of("approval", Table.cell(text(request, "approvalState"), 200)),
                List.of("merged sha", Table.cell(text(request, "mergedSha"), 200)),
                List.of("version", Table.cell(text(request, "version"), 200)),
                List.of("detail", Table.cell(text(request, "detail"), 200)),
                List.of("created", Table.time(text(request, "createdAt"))),
                List.of("updated", Table.time(text(request, "updatedAt")))));
        List<List<String>> sources = new ArrayList<>();
        request.path("sources").forEach(s -> sources.add(List.of(
                Table.cell(text(s, "kind"), 20),
                Table.cell(text(s, "name"), 60),
                Table.cell(text(s, "ref"), 80),
                Table.cell(s.path("implicit").asBoolean() ? "implicit" : "named", 10),
                Table.cell(text(s, "priority"), 10),
                Table.cell(text(s, "addedBy"), 40))));
        if (!sources.isEmpty()) {
            out.println("Sources:");
            Table.print(out, "  ", List.of("KIND", "NAME", "REF", "HOW", "PRIORITY", "ADDED BY"), sources);
        }
    }

    static String shortId(String id) {
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
