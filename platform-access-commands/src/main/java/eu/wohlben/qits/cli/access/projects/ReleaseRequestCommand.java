package eu.wohlben.qits.cli.access.projects;

import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.Effect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.ReleaseRequestSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.tui.api.Completes;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

@CommandLine.Command(name = "release-request", mixinStandardHelpOptions = true,
        subcommands = {ReleaseRequestCommand.ListCommand.class, ReleaseRequestCommand.CreateCommand.class,
                ReleaseRequestCommand.JoinCommand.class, ReleaseRequestCommand.WithdrawCommand.class,
                ReleaseRequestCommand.ApproveCommand.class, ReleaseRequestCommand.DeclineCommand.class,
                ReleaseRequestCommand.RegenerateCommand.class},
        description = {"Release requests: the one way to release a repository. list shows them (of the whole project, "
                        + "or of one repository), create "
                        + "asks for a branch to be released, join adds a branch to an open request, withdraw "
                        + "ends a request that must not ship, approve lets the fold a request is on go ahead, "
                        + "decline sends it back, and regenerate runs one release output again.",
                "A request folds main and its branches into one commit, and the builds of that commit are its gate. "
                        + "States: PENDING (waiting for its builds), READY, RELEASED (the tag is cut, waiting on its "
                        + "remaining gates), FINALIZED (the tag is merged into main, done), REJECTED (a gating build "
                        + "was red), FAILED (the release itself failed), CONFLICTED (the branches do not merge), "
                        + "WITHDRAWN, OBSOLETE (a later request for the repository superseded this one before it "
                        + "finished)."},
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
                "- approve and decline judge one fold: the request's merged sha. A push that folds the request "
                        + "again leaves the decision behind, and the new fold waits for a decision of its own.",
                "- decline is not withdraw. A declined request goes REJECTED and comes back with the next push to "
                        + "one of its branches; WITHDRAWN is final.",
                "- A release request carries a list of approval requirements, each naming the roles that may "
                        + "satisfy it: person, agent (an agent of the request's own project) or agent@<project "
                        + "slug>. approve and decline print them, with whether each is satisfied and by whom, "
                        + "before reading the request and again after deciding. A person holding qits:admin may "
                        + "satisfy a requirement admitting person; an agent may satisfy one admitting agent or "
                        + "agent@<its project>, including a requirement on the very request it asked for, unless "
                        + "that requirement excludes the request's own authors. Asserted identity headers, "
                        + "service clients and qits:system are refused (HTTP 403) either way.",
                "- --project and --repository may come before or after the command. list, approve and decline work "
                        + "without --repository; approve and decline find the request in the project."})
public class ReleaseRequestCommand implements Runnable {

    /** The tag is cut but the request is still open; version and merged sha are already stamped. */
    static final String RELEASED = "RELEASED";

    /**
     * Open means every state but this one, WITHDRAWN and OBSOLETE. The one the default list leaves
     * out on top of those two: the service adds the last 10 of them.
     */
    static final String FINALIZED = "FINALIZED";

    /** The states that take no more branches: the tag is already cut, or the request is done for. */
    static final Set<String> CLOSED = Set.of(RELEASED, FINALIZED, "WITHDRAWN", "OBSOLETE");

    /** The list query for every request, of every state. */
    static final String ALL = "all";

    @CommandLine.Mixin
    ProjectsOptions options;

    @Completes(ProjectSource.class)
    @CommandLine.Option(names = "--project", paramLabel = "<project>", scope = CommandLine.ScopeType.INHERIT,
            description = "The project: its id, slug or name.")
    String project;

    @Completes(RepositorySource.class)
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
        String wantedRepository = RepositoriesCommand.required(repository, "Name the repository: --repository <id or name>.");
        InProject in = inProject(context);
        JsonNode repo = in.api().repository(in.project(), wantedRepository);
        return new Target(in.api(), in.label(), text(repo, "id"), text(repo, "name"));
    }

    private record Target(ProjectsApi api, String projectLabel, String repoId, String repoName) {
    }

    /**
     * Checks --project, then finds the project. What every command needs before it knows a
     * repository, and all that list, approve and decline need: the project's doors take no
     * repository.
     */
    private InProject inProject(CliContext context) throws CliFailure, InterruptedException {
        String wantedProject = RepositoriesCommand.required(project, RepositoriesCommand.NAME_THE_PROJECT);
        ProjectsApi api = ProjectsApi.connect(context, options.projectsUrl);
        JsonNode found = api.project(wantedProject);
        return new InProject(api, found, ProjectsApi.projectLabel(found));
    }

    private record InProject(ProjectsApi api, JsonNode project, String label) {
    }

    /**
     * The repository's request that {@code wanted} names: its id ({@code <repository>-rr-<n>}, any
     * case), its uuid, or the start of the uuid. It reads every request, of every state. This lookup
     * is also what keeps a command off another repository's request.
     */
    private static JsonNode find(Target target, String wanted) throws CliFailure, InterruptedException {
        return ReleaseRequestRefs.find(target.api().releaseRequests(target.repoId(), ALL).path("requests"), wanted,
                target.repoName(), "`qits release-request --project " + target.projectLabel() + " --repository "
                        + target.repoName() + " list --state all` shows them.");
    }

    /** The state the list showed, when it already takes no more branches; else every state that would. */
    private static String settledState(JsonNode found) {
        String listed = text(found, "state").toUpperCase(Locale.ROOT);
        return CLOSED.contains(listed) ? listed : "RELEASED, FINALIZED, WITHDRAWN or OBSOLETE";
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"List release requests: of the whole project, or of one repository with --repository.",
                    "Without --repository it shows every repository's requests, as the landing app does: the open "
                            + "ones and the last 10 FINALIZED, most recently moved first, with a REPOSITORY and an "
                            + "APPROVAL column.",
                    "With --repository it shows that repository's open requests only. Open means every state but "
                            + "FINALIZED, WITHDRAWN and OBSOLETE.",
                    "--state asks for other ones in both forms. The ID column shows the request's id, "
                            + "<repository>-rr-<n>, which `join` and `withdraw` take. -o json also has its uuid (id); "
                            + "the commands take that too."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits list",
                    "  qits release-request --project qits list --state all --limit 20",
                    "  qits release-request --project qits --repository qits-ci-service list",
                    "  qits release-request --project qits --repository qits-ci-service list --state all -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE|all>",
                description = "Only this state (PENDING, READY, RELEASED, FINALIZED, REJECTED, FAILED, CONFLICTED, "
                        + "WITHDRAWN, OBSOLETE), or all for every request. Default: the open ones (and, for the "
                        + "whole project, the last 10 FINALIZED).")
        String state;

        @CommandLine.Option(names = "--limit", paramLabel = "<n>",
                description = "Show at most this many requests, the most recent first. Default: all that came back.")
        Integer limit;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (limit != null && limit < 1) {
                throw new CliFailure("--limit must be 1 or more.", CliFailure.USAGE);
            }
            boolean openOnly = state == null || state.isBlank();
            boolean wholeProject = parent.repository == null || parent.repository.isBlank();
            String scope;
            JsonNode answer;
            if (wholeProject) {
                InProject in = parent.inProject(context);
                scope = "project " + in.label();
                answer = in.api().projectReleaseRequests(text(in.project(), "id"), state);
            } else {
                Target target = parent.target(context);
                scope = target.repoName();
                answer = target.api().releaseRequests(target.repoId(), state);
                if (openOnly) {
                    answer = withoutFinalized(answer);
                }
            }
            answer = limited(answer, limit);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> requests = new ArrayList<>();
            answer.path("requests").forEach(requests::add);
            if (requests.isEmpty()) {
                context.out().println(openOnly
                        ? "No open release requests for " + scope + "."
                        : "No release requests in state " + state.strip() + " for " + scope + ".");
                return 0;
            }
            if (wholeProject) {
                Table.print(context.out(), "", List.of("ID", "REPOSITORY", "STATE", "APPROVAL", "PRIORITY", "SUMMARY",
                                "VERSION", "UPDATED"),
                        requests.stream().map(r -> List.of(
                                Table.cell(ReleaseRequestRefs.label(r), 60),
                                Table.cell(text(r, "repoName"), 40),
                                Table.cell(text(r, "state"), 12),
                                Table.cell(text(r, "approvalState"), 12),
                                Table.cell(text(r, "priority"), 10),
                                Table.cell(text(r, "summary"), 60),
                                Table.cell(text(r, "version"), 24),
                                Table.time(text(r, "updatedAt")))).toList());
                return 0;
            }
            Table.print(context.out(), "", List.of("ID", "STATE", "PRIORITY", "SUMMARY", "VERSION", "UPDATED"),
                    requests.stream().map(r -> List.of(
                            Table.cell(ReleaseRequestRefs.label(r), 60),
                            Table.cell(text(r, "state"), 12),
                            Table.cell(text(r, "priority"), 10),
                            Table.cell(text(r, "summary"), 60),
                            Table.cell(text(r, "version"), 24),
                            Table.time(text(r, "updatedAt")))).toList());
            return 0;
        }

        /**
         * The service's default answer holds the open requests and the last 10 finalized. The
         * repository list shows open ones, so the finalized are dropped here, in both output forms.
         */
        static JsonNode withoutFinalized(JsonNode answer) {
            return keep(answer, Integer.MAX_VALUE, r -> !FINALIZED.equalsIgnoreCase(text(r, "state")));
        }

        /** The first {@code limit} requests, in both output forms; all of them without a limit. */
        static JsonNode limited(JsonNode answer, Integer limit) {
            return limit == null ? answer : keep(answer, limit, r -> true);
        }

        private static JsonNode keep(JsonNode answer, int most, Predicate<JsonNode> wanted) {
            ObjectNode copy = answer.isObject() ? ((ObjectNode) answer).deepCopy() : JsonNodeFactory.instance.objectNode();
            ArrayNode kept = copy.arrayNode();
            answer.path("requests").forEach(r -> {
                if (kept.size() < most && wanted.test(r)) {
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
                SafeJson.print(context.out(), answer);
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
                    "  qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 "
                            + "--branch feature/log-search",
                    "  qits release-request --project qits --repository qits-ci-service join --request qits-ci-service-rr-12 "
                            + "--branch feature/log-search --priority BLOCKING",
                    "",
                    "- Safe to repeat: a branch already on the request adds nothing. With --priority it states that "
                            + "priority again; without, the branch keeps its priority.",
                    "- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request takes no more branches (HTTP 409): open "
                            + "a new one with `create`."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class JoinCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the "
                        + "uuid.")
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
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a join can expect get a sentence that says what to do next. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + ReleaseRequestRefs.name(found) + " is " + settledState(found)
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
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12",
                    "  qits release-request --project qits --repository qits-ci-service withdraw --request qits-ci-service-rr-12 "
                            + "--reason \"The log view moves to qits-observability\"",
                    "",
                    "- Only for a request that must not ship. A gating build that was red because of the platform, "
                            + "not the code, runs again with `qits ci retry <run id>`. A REJECTED or CONFLICTED request "
                            + "comes back by itself when one of its branches gets a new push.",
                    "- Without --reason the platform writes who withdrew it.",
                    "- A RELEASED, FINALIZED, WITHDRAWN or OBSOLETE request cannot be withdrawn (HTTP 409)."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example a --request that fits no request, or more than one), not signed in, "
                            + "or the session ended."})
    public static class WithdrawCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start of the "
                        + "uuid.")
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
                SafeJson.print(context.out(), answer);
                return 0;
            }
            printRequest(context.out(), answer.path("request"));
            return 0;
        }

        /** The two refusals a withdraw can expect get a sentence of their own. */
        private static CliFailure explain(CliFailure refused, JsonNode found) {
            if (refused.status() == 409) {
                return new CliFailure("Release request " + ReleaseRequestRefs.name(found) + " is " + settledState(found)
                        + " already and cannot be withdrawn (HTTP 409).", CliFailure.FAILED);
            }
            if (refused.status() == 404) {
                return new CliFailure("No such request. " + refused.getMessage(), CliFailure.FAILED);
            }
            return refused;
        }
    }

    /**
     * What approve and decline share: the request found in the project by its logical id or uuid,
     * the fold being judged, the note, and the one door call. Neither needs --repository: the
     * project's door answers the request with its repository, which is all the decision door needs.
     * <p>
     * <b>The fold is the decision's subject.</b> The service records a decision against a merged
     * sha and refuses one whose sha is not the request's current fold (409), so a decision can never
     * land on a fold its maker did not look at. --sha is that check in full. Without it, the command
     * sends the sha it has just read, which only narrows the race to the moment between the read and
     * the write; the help says so. A request with no fold has nothing to judge, and is refused here
     * before anything is sent.
     * <p>
     * <b>The refusals are the service's.</b> Who may decide (a person, by their own sign-in) and when
     * (a fold that is current, a request that waits for one) are the door's rules, and its sentences
     * say which one refused; they reach the person as {@link eu.wohlben.qits.cli.access.platform.PlatformClient}
     * worded them, with no second sentence on top.
     */
    abstract static class DecisionCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @CommandLine.Option(names = "--request", paramLabel = "<id>", required = true,
                description = "The request: its id as list shows it (qits-ci-service-rr-221), in any case, or its "
                        + "whole uuid. It is found in the project, so --repository is not needed.")
        String request;

        @CommandLine.Option(names = "--sha", paramLabel = "<merged sha>",
                description = "The fold you judged: the request's merged sha, as the landing app or -o json shows "
                        + "it. This is the strict form: the platform refuses the decision (HTTP 409) if the request "
                        + "is on another fold by then. Without it the command judges whatever fold the request is "
                        + "on at the moment it is read.")
        String sha;

        @CommandLine.Option(names = "--note", paramLabel = "<text>",
                description = "Why, in a sentence, for the record. A decline's note becomes the request's detail, "
                        + "the first thing whoever answers the decline reads. Default: none.")
        String note;

        /** The door's verb, as a sentence says it: approve, decline. */
        abstract String verb();

        /** What the first line of the table form says was done: Approved, Declined. */
        abstract String done();

        abstract JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (request.isBlank() || (sha != null && sha.isBlank())) {
                throw new CliFailure("--request and --sha must not be empty.", CliFailure.USAGE);
            }
            InProject in = parent.inProject(context);
            JsonNode found = in.api().projectReleaseRequest(text(in.project(), "id"), request.strip())
                    .path("request");
            String name = ReleaseRequestRefs.name(found);
            String fold = sha != null ? sha.strip() : text(found, "mergedSha");
            if (fold.isBlank()) {
                throw new CliFailure("Release request " + name + " has not been folded yet, so there is no fold to "
                        + verb() + ".", CliFailure.FAILED);
            }
            if (!json) {
                printRequirements(context.out(), "Requirements before " + verb() + ":", found);
            }
            JsonNode answer = decide(in.api(), text(found, "repoId"), text(found, "id"), fold, note);
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            context.out().println(done() + " " + name + " at " + fold + ".");
            printRequest(context.out(), answer.path("request"));
            printRequirements(context.out(), "Requirements after " + verb() + ":", answer.path("request"));
            return 0;
        }
    }

    @CommandLine.Command(name = "approve", mixinStandardHelpOptions = true,
            description = {"Approve the fold a release request is on, so it may release once its gates are green.",
                    "The approval is for one fold, the request's merged sha. A push that folds the request again "
                            + "leaves it behind, and the new fold needs an approval of its own. It prints the "
                            + "request's approval requirements, with their admitted roles and whether each is "
                            + "satisfied, before the decision and again after, then the request that came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits approve --request qits-ci-service-rr-221 "
                            + "--sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e",
                    "  qits release-request --project qits approve --request qits-ci-service-rr-221 "
                            + "--note \"Read the diff: the pin moves to the export release.\"",
                    "",
                    "- Give --sha to approve exactly the fold you read. Without it the command approves whatever "
                            + "fold the request is on when it reads it.",
                    "- A person holding qits:admin may satisfy a requirement that admits person. An agent may "
                            + "satisfy a requirement that admits agent or agent@<its project>, including one on "
                            + "the very request it asked for, unless that requirement excludes the request's own "
                            + "authors. Asserted identity headers, service clients and qits:system are refused "
                            + "(HTTP 403) either way; the message names each open requirement and the roles it "
                            + "admits.",
                    "- HTTP 409 means the request is on another fold than the one sent (the message names the "
                            + "one it is on now), has no fold, has concluded or is being released, or every "
                            + "requirement at the fold is already satisfied. Read it again and judge the fold it "
                            + "is on."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (the message names the status and says why), cannot be reached, or "
                            + "the request has no fold yet, so there is nothing to approve.",
                    HelpText.USAGE})
    public static class ApproveCommand extends DecisionCommand {

        @Override
        String verb() {
            return "approve";
        }

        @Override
        String done() {
            return "Approved";
        }

        @Override
        JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException {
            return api.approveReleaseRequest(repoId, requestId, mergedSha, note);
        }
    }

    @CommandLine.Command(name = "decline", mixinStandardHelpOptions = true,
            description = {"Decline the fold a release request is on, so it does not release.",
                    "The request goes REJECTED and comes back by itself when one of its branches gets a new push, "
                            + "which makes a new fold to judge. It is not withdraw: WITHDRAWN is final. It prints "
                            + "the request's approval requirements, with their admitted roles and whether each is "
                            + "satisfied, before the decision and again after, then the request that came back."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits decline --request qits-ci-service-rr-221 "
                            + "--sha 3f9c2e1a7b8d4c6e5f0a1b2c3d4e5f6a7b8c9d0e "
                            + "--note \"The README promises an approval policy nobody has signed off yet.\"",
                    "",
                    "- Give --sha to decline exactly the fold you read. Without it the command declines whatever "
                            + "fold the request is on when it reads it.",
                    "- To stop a request for good, use `withdraw` instead.",
                    "- A person holding qits:admin may satisfy a requirement that admits person. An agent may "
                            + "satisfy a requirement that admits agent or agent@<its project>, including one on "
                            + "the very request it asked for, unless that requirement excludes the request's own "
                            + "authors. Asserted identity headers, service clients and qits:system are refused "
                            + "(HTTP 403) either way; the message names each requirement it concerns and the "
                            + "roles it admits.",
                    "- HTTP 409 means the request is on another fold than the one sent (the message names the "
                            + "one it is on now), has no fold, has concluded or is being released, or its fold has "
                            + "no approval requirement at all. Read it again and judge the fold it is on."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE,
                    "1:The platform refused (the message names the status and says why), cannot be reached, or "
                            + "the request has no fold yet, so there is nothing to decline.",
                    HelpText.USAGE})
    public static class DeclineCommand extends DecisionCommand {

        @Override
        String verb() {
            return "decline";
        }

        @Override
        String done() {
            return "Declined";
        }

        @Override
        JsonNode decide(ProjectsApi api, String repoId, String requestId, String mergedSha, String note)
                throws CliFailure, InterruptedException {
            return api.declineReleaseRequest(repoId, requestId, mergedSha, note);
        }
    }

    @CommandLine.Command(name = "regenerate", mixinStandardHelpOptions = true,
            description = {"Generate one release output again on an open release request: the entity diagram, the "
                    + "screenshot baselines, the dependency bump, the estate pins, or the golden recordings (maven "
                    + "or npm).",
                    "QA checks the entity diagram and the screenshot baselines and fails when they are stale; it "
                            + "does not regenerate them. This command asks for the run. The automation commits the "
                            + "output to maintenance/automations/<kind>/<request id>, the request folds again with "
                            + "it, and QA then runs again. It prints the run's id."},
            footerHeading = HelpText.EXAMPLES,
            footer = {
                    "  qits release-request --project qits --repository qits-landing-app regenerate "
                            + "--kind screenshot-baselines",
                    "  qits release-request --project qits --repository qits-projects-service regenerate "
                            + "--request qits-projects-service-rr-12 --kind entity-diagram",
                    "",
                    "- Without --request it takes the repository's one open request. A REJECTED request is open: "
                            + "a red QA is the usual reason to regenerate.",
                    "- 409 means a run of this kind is already active for the request, the request is not open or "
                            + "has no fold, or bumping is off. 404 means the platform knows no such kind or "
                            + "request. 503 means qits-maintenance cannot be reached; nothing ran.",
                    "- `qits maintenance automations --request <id>` shows each kind's state, and "
                            + "`qits maintenance bump <run id>` how the run went."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED,
                    "2:Used wrongly (for example no --request when the repository has no open request or more "
                            + "than one), not signed in, or the session ended."})
    public static class RegenerateCommand extends PlatformCommand {

        /** The kinds the platform runs today; the service is the authority, this list is for the sentences. */
        static final String KINDS = "entity-diagram, screenshot-baselines, dependency-bump, estate-pins, "
                + "golden-recordings-maven and golden-recordings-npm";

        @CommandLine.ParentCommand
        ReleaseRequestCommand parent;

        @Completes(ReleaseRequestSource.class)
        @CommandLine.Option(names = "--request", paramLabel = "<id>",
                description = "The request: its id as list shows it (qits-ci-service-rr-12), its uuid, or the start "
                        + "of the uuid. Default: the repository's one open request.")
        String request;

        @CommandLine.Option(names = "--kind", paramLabel = "<kind>", required = true,
                description = "What to generate: entity-diagram, screenshot-baselines, dependency-bump, "
                        + "estate-pins, golden-recordings-maven or golden-recordings-npm.")
        String kind;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = json(parent.options.output);
            if (kind.isBlank() || (request != null && request.isBlank())) {
                throw new CliFailure("--kind and --request must not be empty.", CliFailure.USAGE);
            }
            String wantedKind = kind.strip().toLowerCase(Locale.ROOT);
            Target target = parent.target(context);
            JsonNode found = request == null ? onlyOpen(target) : find(target, request.strip());
            String requestId = text(found, "id");
            String requestName = ReleaseRequestRefs.name(found);
            JsonNode answer;
            try {
                answer = target.api().rerunReleaseRequestAutomation(target.repoId(), requestId, wantedKind);
            } catch (CliFailure refused) {
                throw explain(refused, requestName, ReleaseRequestRefs.label(found), wantedKind);
            }
            if (json) {
                SafeJson.print(context.out(), answer);
                return 0;
            }
            String runId = text(answer, "id");
            context.out().println("Asked for " + wantedKind + " on release request " + requestName + ": run " + runId);
            context.out().println("The request folds again when the output is committed to maintenance/automations/"
                    + wantedKind + "/" + requestId + "; QA then runs again.");
            context.out().println("Follow the run with `qits maintenance bump " + runId + "`.");
            return 0;
        }

        /** The repository's one request that is still open; anything else needs --request. */
        private static JsonNode onlyOpen(Target target) throws CliFailure, InterruptedException {
            List<JsonNode> open = new ArrayList<>();
            target.api().releaseRequests(target.repoId(), null).path("requests").forEach(r -> {
                if (!CLOSED.contains(text(r, "state").toUpperCase(Locale.ROOT))) {
                    open.add(r);
                }
            });
            if (open.isEmpty()) {
                throw new CliFailure("Repository " + target.repoName() + " has no open release request. "
                        + "Name one with --request.", CliFailure.USAGE);
            }
            if (open.size() > 1) {
                throw new CliFailure("Repository " + target.repoName() + " has more than one open release request: "
                        + open.stream().map(r -> ReleaseRequestRefs.label(r) + " (" + text(r, "state") + ")")
                                .collect(Collectors.joining(", "))
                        + ". Name one with --request.", CliFailure.USAGE);
            }
            return open.getFirst();
        }

        /** The three refusals the door names get a sentence that says what to do next. */
        private static CliFailure explain(CliFailure refused, String requestName, String requestLabel, String kind) {
            return switch (refused.status()) {
                case 404 -> new CliFailure("The platform knows no kind '" + kind + "' or no release request "
                        + requestName + " (HTTP 404). The kinds are " + KINDS + ". " + refused.getMessage(),
                        CliFailure.FAILED);
                case 409 -> new CliFailure("The platform will not run " + kind + " on release request " + requestName
                        + " now (HTTP 409): a run of it may be active already, the request may not be open or have "
                        + "no fold, or bumping may be off. `qits maintenance automations --request " + requestLabel
                        + "` shows the kind's state. " + refused.getMessage(), CliFailure.FAILED);
                case 503 -> new CliFailure("qits-maintenance cannot be reached or is not set up, so nothing ran "
                        + "(HTTP 503). Try again later. " + refused.getMessage(), CliFailure.FAILED);
                default -> refused;
            };
        }
    }

    static void printRequest(PrintStream out, JsonNode request) {
        out.println("Release request " + ReleaseRequestRefs.name(request));
        List<List<String>> rows = new ArrayList<>();
        if (!text(request, "qualifiedId").isBlank()) {
            rows.add(List.of("uuid", Table.cell(text(request, "id"), 200)));
        }
        rows.addAll(List.of(
                List.of("repository", Table.cell(text(request, "repoName"), 200)),
                List.of("state", Table.cell(text(request, "state"), 200)),
                List.of("superseded by", Table.cell(supersededBy(request), 200)),
                List.of("priority", Table.cell(text(request, "priority"), 200)),
                List.of("summary", Table.cell(text(request, "summary"), 200)),
                List.of("requester", Table.cell(text(request, "requester"), 200)),
                List.of("approval", Table.cell(text(request, "approvalState"), 200)),
                List.of("merged sha", Table.cell(text(request, "mergedSha"), 200)),
                List.of("version", Table.cell(text(request, "version"), 200)),
                List.of("detail", Table.cell(text(request, "detail"), 200)),
                List.of("created", Table.time(text(request, "createdAt"))),
                List.of("updated", Table.time(text(request, "updatedAt")))));
        Table.print(out, "  ", null, rows);
        List<List<String>> gates = new ArrayList<>();
        request.path("gates").forEach(g -> gates.add(List.of(
                Table.cell(text(g, "kind"), 20),
                Table.cell(text(g, "state"), 12),
                Table.cell(text(g, "detail"), 80))));
        if (!gates.isEmpty()) {
            out.println("Gates:");
            Table.print(out, "  ", List.of("KIND", "STATE", "REASON"), gates);
        }
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

    /** The request that superseded this one, by its logical id, else its short uuid; blank when none. */
    private static String supersededBy(JsonNode request) {
        return ReleaseRequestRefs.label(text(request, "supersededByQualifiedId"), text(request, "supersededBy"));
    }

    /**
     * The request's approval requirements (epic qits-1038): one row per requirement, with its
     * admitted roles, whether the request's own authors are excluded, and whether it is satisfied
     * and by whom. Prints nothing when {@code approvalRequirements} is absent or empty, which is
     * both an older service (the field does not exist yet) and a request with no requirement at
     * all; either way there is nothing to show.
     */
    static void printRequirements(PrintStream out, String heading, JsonNode request) {
        JsonNode requirements = request.path("approvalRequirements");
        if (!requirements.isArray() || requirements.isEmpty()) {
            return;
        }
        out.println(heading);
        List<List<String>> rows = new ArrayList<>();
        requirements.forEach(requirement -> rows.add(List.of(
                Table.cell(text(requirement, "id"), 40),
                Table.cell(text(requirement, "reason"), 70),
                Table.cell(admits(requirement), 30),
                requirement.path("excludesAuthors").asBoolean() ? "yes" : "no",
                requirement.path("satisfied").asBoolean() ? "yes" : "no",
                Table.cell(satisfiedBy(requirement), 60))));
        Table.print(out, "  ", List.of("ID", "REASON", "ADMITS", "EXCLUDES AUTHORS", "SATISFIED", "SATISFIED BY"),
                rows);
    }

    /** {@code admits}, joined for one cell: "person", "person, agent", "person, agent@qits". */
    private static String admits(JsonNode requirement) {
        List<String> roles = new ArrayList<>();
        requirement.path("admits").forEach(role -> roles.add(role.asText()));
        return String.join(", ", roles);
    }

    /** Who satisfied the requirement, holding which roles, and when; a dash when nobody has. */
    private static String satisfiedBy(JsonNode requirement) {
        JsonNode by = requirement.path("satisfiedBy");
        if (!by.isObject()) {
            return "-";
        }
        List<String> roles = new ArrayList<>();
        by.path("roles").forEach(role -> roles.add(role.asText()));
        return text(by, "approver") + " (" + String.join(", ", roles) + ") at " + Table.time(text(by, "at"));
    }
}
