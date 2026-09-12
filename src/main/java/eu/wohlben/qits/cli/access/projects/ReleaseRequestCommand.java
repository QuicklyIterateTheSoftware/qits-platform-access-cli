package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "release-request", mixinStandardHelpOptions = true,
        subcommands = {ReleaseRequestCommand.ListCommand.class, ReleaseRequestCommand.CreateCommand.class,
                ReleaseRequestCommand.JoinCommand.class, ReleaseRequestCommand.WithdrawCommand.class},
        description = {"The release requests of one repository: the one way to release it. list shows them, create "
                        + "asks for a branch to be released, join adds a branch to an open request, and withdraw "
                        + "ends a request that must not ship.",
                "A request folds main and its branches into one commit, and the builds of that commit are its gate. "
                        + "States: PENDING (waiting for its builds), READY, RELEASED, REJECTED (a gating build was "
                        + "red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), "
                        + "WITHDRAWN."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- A REJECTED or CONFLICTED request comes back by itself when one of its branches gets a new push. "
                        + "Fix the branch and push; do not open a new request.",
                "- When a red build was the platform's fault and not the code's (a flaked container, a registry "
                        + "that was down), retry that run with `qits ci retry <run id>`: it builds the same commit "
                        + "again. `qits ci runs --release-request <id>` finds the request's runs. Do not open a new "
                        + "request, and do not withdraw this one.",
                "- `withdraw` is only for a request that must not ship: the change is wrong, or nobody wants it any "
                        + "more. WITHDRAWN is final.",
                "- --project and --repository may come before or after the command."})
public class ReleaseRequestCommand implements Runnable {

    /** The one state the default list leaves out: the service adds the last 10 of them. */
    static final String RELEASED = "RELEASED";

    /** The states that take no more branches. */
    static final Set<String> SETTLED = Set.of(RELEASED, "WITHDRAWN");

    /** The list query for every request, of every state. */
    static final String ALL = "all";

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
        JsonNode found = api.project(wantedProject);
        JsonNode repo = api.repository(found, wantedRepository);
        return new Target(api, ProjectsApi.projectLabel(found), text(repo, "id"), text(repo, "name"));
    }

    private record Target(ProjectsApi api, String projectLabel, String repoId, String repoName) {
    }

    /**
     * The repository's request whose id starts with {@code wanted}; a full id fits only its own
     * request. It reads every request, of every state. The service finds a request by its id alone,
     * so this lookup is also what keeps a command off another repository's request.
     */
    private static JsonNode find(Target target, String wanted) throws CliFailure, InterruptedException {
        String start = wanted.toLowerCase(Locale.ROOT);
        List<JsonNode> matches = new ArrayList<>();
        target.api().releaseRequests(target.repoId(), ALL).path("requests").forEach(r -> {
            if (text(r, "id").toLowerCase(Locale.ROOT).startsWith(start)) {
                matches.add(r);
            }
        });
        if (matches.isEmpty()) {
            throw new CliFailure("Repository " + target.repoName() + " has no release request whose id starts with '"
                    + wanted + "'. `qits release-request --project " + target.projectLabel() + " --repository "
                    + target.repoName() + " list --state all` shows them.", CliFailure.USAGE);
        }
        if (matches.size() > 1) {
            throw new CliFailure("'" + wanted + "' is the start of more than one release request of "
                    + target.repoName() + ": "
                    + matches.stream().map(r -> text(r, "id") + " (" + text(r, "state") + ")")
                            .collect(Collectors.joining(", "))
                    + ". Give more of the id.", CliFailure.USAGE);
        }
        return matches.getFirst();
    }

    /** The state the list showed, when it is a settled one; else both, as the request may have settled since. */
    private static String settledState(JsonNode found) {
        String listed = text(found, "state").toUpperCase(Locale.ROOT);
        return SETTLED.contains(listed) ? listed : "RELEASED or WITHDRAWN";
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List the repository's open release requests.",
                    "Open means every state but RELEASED and WITHDRAWN. --state asks for other ones. The ID column "
                            + "shows the first 8 characters of the id, which is enough for `join`."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service list",
                    "  qits release-request --project qits --repository qits-ci-service list --state all -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
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
                            + "or (on a project wrapper) the open request the branch joined. It prints what came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service create --branch "
                            + "feature/log-view --summary \"Show the build log live\"",
                    "  qits release-request --project qits --repository qits-ci-service create --branch main "
                            + "--summary \"Release main\" --priority HIGH",
                    "",
                    "- Asking again for a branch that is on an open request answers that request; it opens no "
                            + "second one. Check the id that comes back.",
                    "- On a project wrapper the answer can be a request somebody else opened. Its summary stands, "
                            + "and a red gate holds every branch on it.",
                    "- To add another branch to a request you have, use `join`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
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

    @CommandLine.Command(name = "join", mixinStandardHelpOptions = true,
            description = {"Add a branch to an open release request.",
                    "The platform folds the request again with the branch and, if that makes a new commit, "
                            + "builds that commit. It prints the request that came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 "
                            + "--branch feature/log-search",
                    "  qits release-request --project qits --repository qits-ci-service join --request 4f2a91c0 "
                            + "--branch feature/log-search --priority BLOCKING",
                    "",
                    "- Safe to repeat: a branch already on the request adds nothing. With --priority it states that "
                            + "priority again; without, the branch keeps its priority.",
                    "- A RELEASED or WITHDRAWN request takes no more branches (HTTP 409): open a new one with "
                            + "`create`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class JoinCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id, or enough of its start to name one (list shows 8 characters).")
        String request;

        @CommandLine.Option(names = "--branch", paramLabel = "<branch>", required = true,
                description = "The branch to add.")
        String branch;

        @CommandLine.Option(names = "--priority", paramLabel = "<priority>",
                description = "LOWEST, LOW, MEDIUM, HIGH, HIGHER or BLOCKING. Default: MEDIUM for a new branch; "
                        + "a branch already on the request keeps its priority.")
        String priority;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || branch.isBlank()) {
                throw new CliFailure("--request and --branch must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer;
            try {
                answer = target.api().joinReleaseRequest(target.repoId(), text(found, "id"), branch.strip(), priority);
            } catch (CliFailure refused) {
                throw explain(refused, found);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a join can expect get a sentence that says what to do next. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + text(found, "id") + " is " + settledState(found)
                        + " and takes no more branches (HTTP 409). Open a new one with `qits release-request create`.",
                        CliFailure.FAILED);
            }
            if (refused.status() == 404) {
                return new CliFailure("No such request or branch. " + refused.getMessage(), CliFailure.FAILED);
            }
            return refused;
        }
    }

    @CommandLine.Command(name = "withdraw", mixinStandardHelpOptions = true,
            description = {"Withdraw an open release request, so it does not ship.",
                    "WITHDRAWN is final: the request is not built or released again, and its branches are free. "
                            + "The next `create` for one of them opens a new request. It prints the request that "
                            + "came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0",
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request 4f2a91c0 "
                            + "--reason \"The log view moves to qits-observability\"",
                    "",
                    "- Only for a request that must not ship. A gating build that was red because of the platform, "
                            + "not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request "
                            + "comes back by itself when one of its branches gets a new push.",
                    "- Without --reason the platform writes who withdrew it.",
                    "- A RELEASED or WITHDRAWN request cannot be withdrawn (HTTP 409)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class WithdrawCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id, or enough of its start to name one (list shows 8 characters).")
        String request;

        @CommandLine.Option(names = "--reason", paramLabel = "<text>",
                description = "Why it must not ship, in a sentence. The request shows it as its detail. "
                        + "Default: the platform writes who withdrew it.")
        String reason;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank()) {
                throw new CliFailure("--request must not be empty.", CliFailure.USAGE);
            }
            Target target = parent.target(context);
            JsonNode found = find(target, request.strip());
            JsonNode answer;
            try {
                answer = target.api().withdrawReleaseRequest(target.repoId(), text(found, "id"), reason);
            } catch (CliFailure refused) {
                throw explain(refused, found);
            }
            if (json) {
                ProjectsApi.printJson(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a withdraw can expect get a sentence of their own. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + text(found, "id") + " is " + settledState(found)
                        + " already and cannot be withdrawn (HTTP 409).", CliFailure.FAILED);
            }
            if (refused.status() == 404) {
                return new CliFailure("No such request. " + refused.getMessage(), CliFailure.FAILED);
            }
            return refused;
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
