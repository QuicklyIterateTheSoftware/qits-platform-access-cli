package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.events.SseParser;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.PlatformUrls;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.complete.ProjectSource;
import eu.wohlben.qits.cli.access.complete.RepositorySource;
import eu.wohlben.qits.cli.access.projects.ProjectsApi;
import eu.wohlben.qits.cli.access.terminal.LocalTerminal;
import eu.wohlben.qits.cli.access.terminal.TerminalAttach;
import eu.wohlben.qits.cli.tui.api.Completes;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.IOException;
import java.net.URI;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;

/**
 * {@code qits workspaces}: every action the landing app's workspace pages offer, one command each,
 * on the same qits-workspaces door the page calls (qits-1261). The slots and their containers are
 * here; a slot's container (sign-in, plugins, refine) is {@link WorkspaceAgentsCommand}. The nodes
 * slots are placed on are {@code qits targets} (epic qits-1481, qits-1534): {@code qits workspaces
 * runners} is retired.
 * <p>
 * The agents themselves are {@code qits agents} ({@link AgentCommands}, epic qits-1667); their
 * deprecated aliases under {@code qits workspaces agents} lived for one release and are gone.
 */
@CommandLine.Command(name = "workspaces", mixinStandardHelpOptions = true,
        subcommands = {WorkspacesCommand.ListCommand.class, WorkspacesCommand.ShowCommand.class,
                WorkspacesCommand.ActiveProcessCommand.class, WorkspacesCommand.StartCommand.class,
                WorkspacesCommand.StopCommand.class, WorkspacesCommand.RecreateCommand.class,
                WorkspacesCommand.LogCommand.class, WorkspacesCommand.AttachCommand.class,
                WorkspacesCommand.ItemsCommand.class,
                WorkspacesCommand.HistoryCommand.class, WorkspaceAgentsCommand.class},
        description = {"The workspaces of qits-workspaces, with every action the landing app's workspace pages "
                        + "offer, each on the same door the page calls: the slots and their containers, and a "
                        + "container's sign-in and plugins (agents). The agents themselves are `qits agents`. The "
                        + "nodes slots are placed on are `qits targets`.",
                "A slot is a workspace row, named by its number (`qits workspaces list` shows it). It hosts agents; "
                        + "an agent works on one work item. Starting a container is a technical process, whose log "
                        + "`qits workspaces log` follows."},
        footerHeading = "%nNotes:%n",
        footer = {
                "- Reads are open to every credential, qits:agent included. Writes on the JSON API need qits:admin, "
                        + "qits:admin-agent or qits:desk-admin (the delivery desk, within its own project); "
                        + "qits:agent alone is refused (HTTP 403) by design. Each command's help names its door.",
                "- A container's sign-in and plugins (agents sign-in, agents plugins) come from the slot's daemon, "
                        + "through qits-workspaces' container proxy, which checks the caller: a person, qits:system, "
                        + "qits:admin or qits:admin-agent may reach any slot, qits:agent only its own workspace's. "
                        + "qits:desk-admin is judged as qits:agent there: the slot is a shell that can push.",
                "- `qits workspaces attach` is the agent's Terminal tab: it attaches this terminal to a command's "
                        + "terminal on the slot's daemon. The live chat socket stays the landing app's: the CLI reads "
                        + "the chat's transcript and sends prompts.",
                "- --workspaces-url, --projects-url and --output may come before or after the command."})
public class WorkspacesCommand implements Runnable, WorkspacesAccess {

    private static final Pattern UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @CommandLine.Option(names = "--workspaces-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The workspaces service's base URL, without /workspaces. Default: QITS_WORKSPACES_URL, "
                    + "else derived from the idp address, like the other services.")
    String workspacesUrl;

    @CommandLine.Option(names = "--projects-url", paramLabel = "<url>", scope = CommandLine.ScopeType.INHERIT,
            description = "The projects service's base URL, without /projects, where a work item's qualified id "
                    + "(qits-123) is looked up. Default: QITS_PROJECTS_URL, else derived from the idp address.")
    String projectsUrl;

    @CommandLine.Option(names = {"-o", "--output"}, paramLabel = "table|json", scope = CommandLine.ScopeType.INHERIT,
            description = "table (the default): for a person. json: the service's answer, pretty-printed, with "
                    + "control characters written as escapes.")
    String output;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    @Override
    public WorkspacesApi api(CliContext context) throws CliFailure, InterruptedException {
        return api(context, workspacesUrl);
    }

    @Override
    public boolean json() throws CliFailure {
        return Json.wanted(output);
    }

    JsonNode workItem(CliContext context, String given) throws CliFailure, InterruptedException {
        return workItem(context, projectsUrl, given);
    }

    @Override
    public String workId(CliContext context, String given) throws CliFailure, InterruptedException {
        return workId(context, projectsUrl, given);
    }

    static WorkspacesApi api(CliContext context, String workspacesUrl) throws CliFailure, InterruptedException {
        return new WorkspacesApi(new PlatformClient(context.credential()),
                PlatformUrls.workspaces(workspacesUrl, context.env(), context.idpUrl()));
    }

    /**
     * A work item's id, as qits-workspaces keys agents by it: a uuid is taken as it is, anything else
     * (qits-123) is looked up in the projects service. The item is answered whole, or null for a uuid.
     */
    static JsonNode workItem(CliContext context, String projectsUrl, String given)
            throws CliFailure, InterruptedException {
        String wanted = given == null ? "" : given.strip();
        if (wanted.isEmpty()) {
            throw new CliFailure("Name the work item: its qualified id (qits-123) or its id.", CliFailure.USAGE);
        }
        if (UUID.matcher(wanted).matches()) {
            return null;
        }
        JsonNode item = ProjectsApi.connect(context, projectsUrl).work(wanted);
        if (text(item, "id").isEmpty()) {
            throw new CliFailure("The projects service answered " + SafeText.line(wanted) + " without its id.",
                    CliFailure.FAILED);
        }
        return item;
    }

    static String workId(CliContext context, String projectsUrl, String given) throws CliFailure, InterruptedException {
        JsonNode item = workItem(context, projectsUrl, given);
        return item == null ? given.strip() : text(item, "id");
    }

    static boolean isUuid(String value) {
        return value != null && UUID.matcher(value.strip()).matches();
    }

    @Override
    public String projectId(CliContext context, String given) throws CliFailure, InterruptedException {
        return projectId(context, projectsUrl, given);
    }

    /**
     * {@code --project}'s value resolved to the project's uuid, the way other commands resolve
     * their own {@code --project} (over {@link ProjectsApi#project}).
     */
    static String projectId(CliContext context, String projectsUrl, String given)
            throws CliFailure, InterruptedException {
        String wanted = given == null ? "" : given.strip();
        if (wanted.isEmpty()) {
            throw new CliFailure("Name the project: --project <id, slug or name>.", CliFailure.USAGE);
        }
        JsonNode found = ProjectsApi.connect(context, projectsUrl).project(wanted);
        return text(found, "id");
    }

    /**
     * The wrapper repository's id, as the workspaces service wants it: a uuid is sent as it is, and
     * anything else is looked up in the projects service with {@code --project}, the same lookup
     * {@code qits ci runs} and {@code qits release-request} do (their {@code --project} and
     * {@code --repository}, over {@link ProjectsApi#project} and {@link ProjectsApi#repository}).
     */
    String repositoryId(CliContext context, String project, String repository) throws CliFailure, InterruptedException {
        String wanted = repository == null ? "" : repository.strip();
        if (wanted.isEmpty()) {
            throw new CliFailure("Name the repository: --repository <id or name>.", CliFailure.USAGE);
        }
        if (isUuid(wanted)) {
            return wanted;
        }
        String wantedProject = project == null ? "" : project.strip();
        if (wantedProject.isEmpty()) {
            throw new CliFailure("Name the project too: --project <id, slug or name>, since --repository '"
                    + wanted + "' is not its uuid.", CliFailure.USAGE);
        }
        ProjectsApi api = ProjectsApi.connect(context, projectsUrl);
        JsonNode found = api.project(wantedProject);
        JsonNode repo = api.repository(found, wanted);
        return text(repo, "id");
    }

    /** {@code -o}: the shared reading of table or json, and the one JSON printer. */
    static final class Json {

        private static final ObjectMapper PLAIN = new ObjectMapper();

        private Json() {
        }

        static boolean wanted(String output) throws CliFailure {
            if (output == null || output.equals("table")) {
                return false;
            }
            if (output.equals("json")) {
                return true;
            }
            throw new CliFailure("--output must be table or json, not '" + output + "'.", CliFailure.USAGE);
        }

        static void print(PrintStream out, JsonNode node) throws CliFailure {
            try {
                out.println(SafeText.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));
            } catch (JsonProcessingException impossible) {
                throw new CliFailure("cannot print the answer as JSON", CliFailure.FAILED);
            }
        }

        /** One line of JSON, control characters escaped: a streamed frame. */
        static String line(JsonNode node) {
            try {
                return SafeText.JSON.writeValueAsString(node);
            } catch (JsonProcessingException impossible) {
                return "\"\"";
            }
        }

        static JsonNode parse(String text) {
            try {
                JsonNode node = PLAIN.readTree(text == null ? "" : text);
                return node == null || node.isMissingNode() ? null : node;
            } catch (IOException notJson) {
                return null;
            }
        }
    }

    /** A cell from a field: one line, at most {@code max} characters, a dash for nothing. */
    static String cell(JsonNode node, String field, int max) {
        return Table.cell(text(node, field), max);
    }

    /** The entries of an envelope: {@code {entries: [{<inner>: …}]}} or {@code {<list>: […]}}. */
    static List<JsonNode> entries(JsonNode answer, String list, String inner) {
        List<JsonNode> result = new ArrayList<>();
        JsonNode array = answer.isArray() ? answer : answer.path(list);
        for (JsonNode node : array) {
            result.add(inner != null && node.path(inner).isObject() ? node.get(inner) : node);
        }
        return result;
    }

    /** A slot's fields, for a person; the strip the slot's page shows. */
    static void printSlot(PrintStream out, JsonNode slot) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("workspace", cell(slot, "workspaceId", 120)));
        rows.add(List.of("container", cell(slot, "runtimeStatus", 40)));
        if (!text(slot, "runtimeError").isEmpty()) {
            rows.add(List.of("error", cell(slot, "runtimeError", 200)));
        }
        rows.add(List.of("placement", cell(slot, "placement", 40)));
        rows.add(List.of("admin", cell(slot, "admin", 10)));
        if (!text(slot, "queuedAt").isEmpty()) {
            rows.add(List.of("queued", Table.time(text(slot, "queuedAt"))));
        }
        if (slot.has("daemonConnectedAt") || slot.has("daemonVersion")) {
            rows.add(List.of("daemon", text(slot, "daemonConnectedAt").isEmpty() ? "not connected"
                    : "connected " + Table.time(text(slot, "daemonConnectedAt"))));
            rows.add(List.of("daemon version", cell(slot, "daemonVersion", 60)
                    + (slot.path("daemonOutdated").asBoolean(false) ? " (outdated)" : "")));
        }
        Table.print(out, "  ", null, rows);
    }

    /** The slot's row id: a positive number. */
    static long slot(Long given) throws CliFailure {
        if (given == null || given <= 0) {
            throw new CliFailure("Name the workspace slot: its number, as `qits workspaces list` shows it.",
                    CliFailure.USAGE);
        }
        return given;
    }

    // --- the slots ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The ACTIVE workspace slots of one repository, named by --repository: each with its "
                    + "container and placement. The project's Workspaces page.",
                    "GET /workspaces/api/workspaces?repositoryId=. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces list --repository 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a",
                    "  qits workspaces list --project qits --repository qits-ci-service"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @Completes(ProjectSource.class)
        @CommandLine.Option(names = "--project", paramLabel = "<project>",
                description = "The project: its id, slug or name. Needed only when --repository names the "
                        + "repository rather than giving its uuid.")
        String project;

        @Completes(RepositorySource.class)
        @CommandLine.Option(names = "--repository", paramLabel = "<repository>", required = true,
                description = "The wrapper repository whose slots to list: its uuid (as `qits repositories list "
                        + "-o json` shows it), or, with --project, its id or name.")
        String repository;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String repositoryId = parent.repositoryId(context, project, repository);
            JsonNode answer = parent.api(context).workspaces(repositoryId);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> slots = entries(answer, "entries", "workspace");
            if (slots.isEmpty()) {
                context.out().println("No workspace slots.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode slot : slots) {
                rows.add(List.of(cell(slot, "id", 12), cell(slot, "workspaceId", 50), cell(slot, "runtimeStatus", 20),
                        cell(slot, "placement", 20), cell(slot, "admin", 6), Table.time(text(slot, "queuedAt"))));
            }
            Table.print(context.out(), "", List.of("SLOT", "WORKSPACE", "CONTAINER", "PLACEMENT", "ADMIN",
                    "QUEUED"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"One workspace slot: its container (and its error), placement and daemon, then the live "
                    + "agents placed in it, as `qits agents list` prints them. The status strip of a slot's page.",
                    "GET /workspaces/api/workspaces/{id}, then GET /workspaces/api/workspaces/{id}/agents. With -o "
                            + "json, only the first answer, as it always was; the agents' own JSON is `qits agents "
                            + "list --workspace <n> -o json`. A service too old to list a slot's agents (HTTP 404) "
                            + "leaves them out. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces show 1", "  qits workspaces show 1 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<workspace>", description = "The slot's number.")
        Long workspace;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            long id = slot(workspace);
            JsonNode answer = parent.api(context).workspace(id);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            JsonNode slot = answer.path("workspace").isObject() ? answer.get("workspace") : answer;
            context.out().println("Workspace slot " + id);
            printSlot(context.out(), slot);
            JsonNode agents;
            try {
                agents = parent.api(context).workspaceAgents(id);
            } catch (CliFailure failure) {
                if (failure.status() != 404) {
                    throw failure;
                }
                // The slot answered a moment ago, so a 404 here is a service without the door yet.
                return 0;
            }
            context.out().println();
            AgentViews.printAgents(context.out(), entries(agents, "agents", null), "No agents are placed in it.");
            return 0;
        }
    }

    @CommandLine.Command(name = "active-process", mixinStandardHelpOptions = true,
            description = {"The technical process starting a slot's container, if one is live. Its log is "
                    + "`qits workspaces log <process id>`.",
                    "GET /workspaces/api/workspaces/{id}/active-process. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces active-process 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ActiveProcessCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<workspace>", description = "The slot's number.")
        Long workspace;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode answer = parent.api(context).activeProcess(slot(workspace));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            String process = text(answer, "technicalProcessId");
            context.out().println(process.isEmpty() ? "No start is running."
                    : "Active process " + SafeText.line(process) + ". Follow it with `qits workspaces log "
                    + SafeText.line(process) + "`.");
            return 0;
        }
    }

    /** start, stop and recreate: one container verb each, no body, as the slot's buttons send them. */
    abstract static class ContainerVerb extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<workspace>", description = "The slot's number.")
        Long workspace;

        abstract String verb();

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            long id = slot(workspace);
            JsonNode answer = parent.api(context).containerVerb(id, verb());
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            String process = text(answer, "technicalProcessId");
            if (!process.isEmpty()) {
                context.out().println("Workspace slot " + id + ": started process " + SafeText.line(process)
                        + ". Follow it with `qits workspaces log " + SafeText.line(process) + "`.");
            } else {
                JsonNode slot = answer.path("workspace").isObject() ? answer.get("workspace") : answer;
                context.out().println("Workspace slot " + id + ": container " + cell(slot, "runtimeStatus", 40) + ".");
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "start", mixinStandardHelpOptions = true,
            description = {"Bring a slot's container up, or join the start already running. Start on a slot's page.",
                    "POST /workspaces/api/workspaces/{id}/ensure-container, no body. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces start 1",
                    "",
                    "- A slot on a target that is not available is refused: HTTP 409."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class StartCommand extends ContainerVerb {
        @Override
        String verb() {
            return "ensure-container";
        }
    }

    @CommandLine.Command(name = "stop", mixinStandardHelpOptions = true,
            description = {"Stop a slot's container. Stop on a slot's page.",
                    "POST /workspaces/api/workspaces/{id}/stop-container, no body. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces stop 1",
                    "",
                    "- A slot on a target that is not available is refused: HTTP 409."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class StopCommand extends ContainerVerb {
        @Override
        String verb() {
            return "stop-container";
        }
    }

    @CommandLine.Command(name = "recreate", mixinStandardHelpOptions = true,
            description = {"Replace a slot's container with a new one. Recreate on a slot's page.",
                    "POST /workspaces/api/workspaces/{id}/recreate-container, no body. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces recreate 1",
                    "",
                    "- Refused (HTTP 400) unless the service can prove the working tree clean: commit or discard "
                            + "the changes, and have the daemon connected, first. A target that is not available is "
                            + "HTTP 409."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RecreateCommand extends ContainerVerb {
        @Override
        String verb() {
            return "recreate-container";
        }
    }

    @CommandLine.Command(name = "items", mixinStandardHelpOptions = true,
            description = {"The work items that have an ACTIVE workspace: the Workspace links on the work pages.",
                    "GET /workspaces/api/work/workspaces. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces items"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ItemsCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            JsonNode answer = parent.api(context).openWorkspaces();
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> open = entries(answer, "entries", "workspace");
            if (open.isEmpty()) {
                context.out().println("No work item has an active workspace.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode workspace : open) {
                rows.add(List.of(cell(workspace, "workId", 40), cell(workspace, "id", 12),
                        cell(workspace, "branch", 60)));
            }
            Table.print(context.out(), "", List.of("WORK ID", "WORKSPACE", "BRANCH"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "history", mixinStandardHelpOptions = true,
            description = {"One work item's workspaces in every state, newest first: state, branch, opened and "
                    + "closed. The work item page's list.",
                    "GET /workspaces/api/work/{workRef}/workspaces. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces history qits-123"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class HistoryCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<work>",
                description = "The work item: its qualified id (qits-123) or its id. The service takes either.")
        String work;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String ref = work == null ? "" : work.strip();
            if (ref.isEmpty()) {
                throw new CliFailure("Name the work item: its qualified id (qits-123) or its id.", CliFailure.USAGE);
            }
            JsonNode answer = parent.api(context).workItemWorkspaces(ref);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> workspaces = entries(answer, "entries", "workspace");
            if (workspaces.isEmpty()) {
                context.out().println("No workspaces for " + SafeText.line(ref) + ".");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode workspace : workspaces) {
                rows.add(List.of(cell(workspace, "id", 12), cell(workspace, "status", 20),
                        cell(workspace, "branch", 60), Table.time(text(workspace, "createdAt")),
                        Table.time(text(workspace, "resolvedAt"))));
            }
            Table.print(context.out(), "", List.of("ID", "STATE", "BRANCH", "OPENED", "CLOSED"), rows);
            return 0;
        }
    }

    // --- the process log ---

    @CommandLine.Command(name = "log", mixinStandardHelpOptions = true,
            description = {"Follow a technical process's log, such as a container's start, until the process ends: "
                    + "its segments, their lines and how each settled. The log of a slot's page.",
                    "Each connection replays what the process logged so far, then streams. With -o json, one frame per "
                            + "line as the service sends it; pings are left out. Stops on SIGINT or SIGTERM too.",
                    "GET /workspaces/api/technical-processes/{id}/events (Server-Sent Events). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces log 00000000-0000-4000-8000-000000000100",
                    "",
                    "- `qits workspaces start` and `qits workspaces active-process` name the process."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The process ended, or the command was stopped.",
                    "1:The platform refused (for example a process it does not know, HTTP 404), or the stream dropped.",
                    HelpText.USAGE})
    @TuiCommand(interaction = Interaction.STREAMING, effect = Effect.READ)
    public static class LogCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<process>", description = "The technical process's id.")
        String process;

        private volatile boolean stopped;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = parent.json();
            String id = process == null ? "" : process.strip();
            if (id.isEmpty()) {
                throw new CliFailure("Name the technical process: its id, as `qits workspaces start` or "
                        + "`qits workspaces active-process` shows it.", CliFailure.USAGE);
            }
            WorkspacesApi api = parent.api(context);
            PlatformClient client = new PlatformClient(context.credential());
            PlatformClient.Connection connection = client.openStream(api.processEvents(id));
            context.onStop().accept(() -> {
                stopped = true;
                connection.abort();
            });
            try (connection) {
                SseParser parser = new SseParser();
                Iterator<String> lines = connection.lines().iterator();
                while (lines.hasNext()) {
                    String line = lines.next();
                    var event = parser.accept(line);
                    if (event.isEmpty()) {
                        continue;
                    }
                    JsonNode frame = Json.parse(event.get().data());
                    if (frame == null || "ping".equals(text(frame, "kind"))) {
                        continue;
                    }
                    if (json) {
                        context.out().println(Json.line(frame));
                    } else {
                        printFrame(context.out(), frame);
                    }
                    context.out().flush();
                    if ("done".equals(text(frame, "kind")) || context.out().checkError()) {
                        return 0;
                    }
                }
                return 0;
            } catch (UncheckedIOException dropped) {
                if (stopped) {
                    return 0;
                }
                throw new CliFailure("The log stream of " + SafeText.line(id) + " dropped before the process ended.",
                        CliFailure.FAILED);
            }
        }

        static void printFrame(PrintStream out, JsonNode frame) {
            String segment = SafeText.line(text(frame, "segment"));
            String status = SafeText.line(text(frame, "status"));
            switch (text(frame, "kind")) {
                case "segment-open" -> out.println("== " + segment);
                case "line" -> out.println(segment.isEmpty() ? SafeText.line(text(frame, "line"))
                        : segment + "  " + SafeText.line(text(frame, "line")));
                case "segment-settled" -> out.println("== " + segment + ": " + (status.isEmpty() ? "settled" : status)
                        + (text(frame, "hint").isEmpty() ? "" : " (" + SafeText.line(text(frame, "hint")) + ")"));
                case "done" -> out.println("== done" + (status.isEmpty() ? "" : ": " + status));
                default -> {
                    // A kind a newer service invents is left out, as the page does.
                }
            }
        }
    }

    // --- a command's terminal ---

    /**
     * Attaches to {@code commandId}'s terminal on {@code slot} until the command ends or the person
     * leaves: the one way in for {@code attach}, {@code agents sign-in open --attach} and {@code agents
     * harness --attach}. Refused in the MCP service, which has no terminal to give.
     */
    static int attach(CliContext context, WorkspacesApi api, long slot, String commandId,
                      LocalTerminal.Factory terminals) throws CliFailure, InterruptedException {
        refuseWithoutATerminal();
        return attach(context, api, api.terminalSocket(slot, commandId), TerminalAttach.Subject.command(commandId),
                terminals);
    }

    /**
     * Attaches to the terminal socket at {@code uri}, named {@code subject} in the log lines: the
     * container proxy's command terminal above, or {@code qits agents attach}'s agent proxy terminal.
     */
    static int attach(CliContext context, WorkspacesApi api, URI uri, TerminalAttach.Subject subject,
                      LocalTerminal.Factory terminals) throws CliFailure, InterruptedException {
        refuseWithoutATerminal();
        // Fails at once without a credential, rather than in the reconnect loop.
        context.credential().bearer();
        TerminalAttach attachment = new TerminalAttach(api.client(), uri, subject, terminals.open(context),
                context.out(), context.err(), context.sleeper(), TerminalAttach.BACKOFF, TerminalAttach.SIZE_POLL);
        context.onStop().accept(attachment::stop);
        return attachment.run();
    }

    /** A tool call in the MCP service would wait for as long as the terminal lives, with nobody typing. */
    static void refuseWithoutATerminal() throws CliFailure {
        if (CliContext.inMcpService()) {
            throw new CliFailure("Attaching needs a terminal, and the MCP service has none: run `qits agents attach` "
                    + "or `qits workspaces attach` in a terminal.", CliFailure.USAGE);
        }
    }

    @CommandLine.Command(name = "attach", mixinStandardHelpOptions = true,
            description = {"Attach this terminal to a command's terminal on a slot's daemon: what you type goes to "
                    + "the command, what it prints comes here. The agent's Terminal tab in the landing app.",
                    "Command ids come from `qits workspaces agents sign-in open` (the sign-in terminal), which also "
                            + "takes --attach, opening and attaching to it at once. An agent's own harness is `qits "
                            + "agents attach`, which takes the agent rather than a command.",
                    "Ctrl-] detaches and leaves the command running; Ctrl-C and every other key go to the command. "
                            + "The window's size goes along and follows the window. The command ending ends this "
                            + "too. A dropped connection attaches again after 0.5, 1, 2, 4 and 4 s, and the screen is "
                            + "cleared before the daemon replays up to 256 KB of what the terminal held.",
                    "With stdin not a terminal (a pipe, a script, `qits tui`) it only streams what the command "
                            + "prints: nothing is typed and the size is left alone. It still ends when the command "
                            + "does.",
                    "WS /workspaces/container/{slot}/terminal/commands/{commandId}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces attach 00000000-0000-4000-8000-000000000100 --workspace 1",
                    "  qits workspaces attach 00000000-0000-4000-8000-000000000100 --workspace 1 > terminal.log",
                    "",
                    "- Detaching never ends the command.",
                    "- Not an MCP tool: it needs a terminal and runs until the command ends."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:The command ended, you detached (Ctrl-]), or it was stopped (SIGINT, SIGTERM); only "
                    + "the first ends the command.",
                    "1:The platform refused the socket, or the connection dropped and five attempts to attach again "
                            + "failed.",
                    HelpText.USAGE})
    @TuiCommand(interaction = Interaction.TERMINAL)
    public static class AttachCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspacesCommand parent;

        @CommandLine.Parameters(paramLabel = "<command>", description = "The command's id.")
        String command;

        @CommandLine.Option(names = "--workspace", paramLabel = "<n>", required = true,
                description = "The slot whose daemon runs the command: its number, as `qits workspaces list` shows "
                        + "it.")
        Long workspace;

        /** The caller's terminal; a test hands its own. */
        LocalTerminal.Factory terminals = LocalTerminal::of;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            String id = command == null ? "" : command.strip();
            if (id.isEmpty()) {
                throw new CliFailure("Name the command: its id, as `qits workspaces agents sign-in open` shows it.",
                        CliFailure.USAGE);
            }
            long slot = slot(workspace);
            return attach(context, parent.api(context), slot, id, terminals);
        }
    }
}
