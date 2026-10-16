package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.observe.SafeText;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.HelpText;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.platform.Table;
import eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.Json;
import eu.wohlben.qits.cli.tui.api.Effect;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import picocli.CommandLine;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static eu.wohlben.qits.cli.access.projects.ProjectsApi.text;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.cell;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.entries;
import static eu.wohlben.qits.cli.access.workspaces.WorkspacesCommand.slot;

/**
 * {@code qits workspaces agents}: what the landing app offers on the Workspaces page and an agent's
 * page and tabs (Chat, Files, Terminal), one command per action (qits-1261). The agents themselves
 * are qits-workspaces' JSON API; what a tab shows of the agent's container is the slot's daemon,
 * through the container proxy, so those commands take the slot with --workspace, as the page does.
 */
@CommandLine.Command(name = "agents", mixinStandardHelpOptions = true,
        subcommands = {WorkspaceAgentsCommand.ListCommand.class, WorkspaceAgentsCommand.CreateCommand.class,
                WorkspaceAgentsCommand.DiscardCommand.class, WorkspaceAgentsCommand.PromptCommand.class,
                WorkspaceAgentsCommand.DraftCommand.class, WorkspaceAgentsCommand.ChatCommand.class,
                WorkspaceAgentsCommand.TerminateCommand.class, WorkspaceAgentsCommand.RefineCommand.class,
                WorkspaceAgentsCommand.HarnessCommand.class, WorkspaceAgentsCommand.FilesCommand.class,
                WorkspaceAgentsCommand.SignInCommand.class, WorkspaceAgentsCommand.PluginsCommand.class},
        description = {"The agents in the workspace slots: list, create and discard them, send one a prompt, keep its "
                        + "prompt draft, read its chat, and what its tabs show of its container (files, harness, "
                        + "sign-in, plugins).",
                "An agent is named by its id (`qits workspaces agents list` shows it); a work item by its qualified "
                        + "id (qits-123) or its id. The commands that read the agent's container take its slot "
                        + "with --workspace."},
        footerHeading = "%nNotes:%n",
        footer = {"- create, discard, prompt, draft save and draft discard need qits:admin or qits:admin-agent; "
                + "qits:agent is refused (HTTP 403) by design. The reads are open to it, and so is everything that "
                + "goes through the container proxy (chat, terminate, refine, harness, files, sign-in, plugins)."})
public class WorkspaceAgentsCommand implements Runnable {

    static final Set<String> STATES = Set.of("ACTIVE", "REMOVED");
    static final Set<String> HARNESSES = Set.of("CLAUDE", "KIMI");

    @CommandLine.ParentCommand
    WorkspacesCommand parent;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
    }

    static String agentId(String given) throws CliFailure {
        String id = given == null ? "" : given.strip();
        if (id.isEmpty()) {
            throw new CliFailure("Name the agent: its id, as `qits workspaces agents list` shows it.",
                    CliFailure.USAGE);
        }
        return id;
    }

    static String required(String given, String what) throws CliFailure {
        String value = given == null ? "" : given.strip();
        if (value.isEmpty()) {
            throw new CliFailure(what, CliFailure.USAGE);
        }
        return value;
    }

    static String harness(String given) throws CliFailure {
        if (given == null || given.isBlank()) {
            return null;
        }
        String name = given.strip().toUpperCase(Locale.ROOT);
        if (!HARNESSES.contains(name)) {
            throw new CliFailure("--harness must be CLAUDE or KIMI, not '" + given.strip() + "'.", CliFailure.USAGE);
        }
        return name;
    }

    // --- the agents ---

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"Agents, newest first: what each works on, its slot, how it runs and what it waits on. "
                    + "Bare, every agent (the project's Workspaces page); --work, one item's (its agent page); "
                    + "--workspace, one slot's (the slot's page); --state ACTIVE, the In Progress tab's.",
                    "GET /workspaces/api/agents[?workId=&workspaceId=&state=]. " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents list --state ACTIVE",
                    "  qits workspaces agents list --work qits-123",
                    "  qits workspaces agents list --workspace 1 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ListCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Option(names = "--work", paramLabel = "<work>",
                description = "Only this work item's agents: its qualified id (qits-123) or its id.")
        String work;

        @CommandLine.Option(names = "--workspace", paramLabel = "<n>", description = "Only the agents this slot hosts.")
        Long workspace;

        @CommandLine.Option(names = "--state", paramLabel = "<STATE>",
                description = "Only the agents in this state: ACTIVE or REMOVED. Default: all.")
        String state;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = agents.parent;
            boolean json = root.json();
            String wanted = state == null || state.isBlank() ? null : state.strip().toUpperCase(Locale.ROOT);
            if (wanted != null && !STATES.contains(wanted)) {
                throw new CliFailure("--state must be ACTIVE or REMOVED, not '" + state.strip() + "'.",
                        CliFailure.USAGE);
            }
            Long slot = workspace == null ? null : slot(workspace);
            String workId = work == null || work.isBlank() ? null : root.workId(context, work);
            JsonNode answer = root.api(context).agents(workId, slot, wanted);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> list = entries(answer, "agents", null);
            if (list.isEmpty()) {
                context.out().println("No agents.");
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode agent : list) {
                rows.add(List.of(cell(agent, "agentId", 40), cell(agent, "workspaceRowId", 8),
                        cell(agent, "entityId", 30), cell(agent, "state", 10), cell(agent, "runState", 12),
                        cell(agent, "harness", 8), cell(agent, "waitingOn", 40), cell(agent, "entityTitle", 50)));
            }
            Table.print(context.out(), "", List.of("AGENT", "SLOT", "ITEM", "STATE", "RUN", "HARNESS", "WAITING ON",
                    "TITLE"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "create", mixinStandardHelpOptions = true,
            description = {"Start an agent on a ticket or an epic, in a harness and posture of your choosing. An item "
                    + "that already has an ACTIVE agent answers that one (fresh: false). Create agent on the "
                    + "Workspaces page.",
                    "POST /workspaces/api/agents with {repositoryId, workId, entityId, kind, admin, harness?, "
                            + "instruction?}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents create --repository 0d9e8f7a-6b5c-4d3e-8f2a-1b0c9d8e7f6a --work qits-123",
                    "  qits workspaces agents create --repository <id> --work qits-120 --kind EPIC --harness CLAUDE "
                            + "--instruction 'Plan the epic first.'",
                    "",
                    "- A qualified id is looked up in the projects service for the item's id; it is sent as entityId, "
                            + "and an EPIC item makes --kind EPIC unless --kind says otherwise."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class CreateCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Option(names = "--repository", paramLabel = "<id>", required = true,
                description = "The wrapper repository the agent works in: its id.")
        String repository;

        @CommandLine.Option(names = "--work", paramLabel = "<work>", required = true,
                description = "The work item: its qualified id (qits-123) or its id.")
        String work;

        @CommandLine.Option(names = "--entity", paramLabel = "<qualified id>",
                description = "The item's qualified id, sent as entityId. Default: the one the lookup of --work found.")
        String entity;

        @CommandLine.Option(names = "--kind", paramLabel = "TICKET|EPIC",
                description = "What the item is. Default: EPIC for an epic the lookup found, else TICKET.")
        String kind;

        @CommandLine.Option(names = "--admin",
                description = "Admin mode: the agent runs in a slot that holds the host's docker socket.")
        boolean admin;

        @CommandLine.Option(names = "--harness", paramLabel = "CLAUDE|KIMI",
                description = "The harness the agent runs in. Default: the service's.")
        String harness;

        @CommandLine.Option(names = "--instruction", paramLabel = "<text>", description = "The agent's first turn.")
        String instruction;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = agents.parent;
            boolean json = root.json();
            String repositoryId = required(repository, "Name the wrapper repository: --repository <id>.");
            String chosenHarness = harness(harness);
            String chosenKind = kind == null || kind.isBlank() ? null : kind.strip().toUpperCase(Locale.ROOT);
            if (chosenKind != null && !chosenKind.equals("TICKET") && !chosenKind.equals("EPIC")) {
                throw new CliFailure("--kind must be TICKET or EPIC, not '" + kind.strip() + "'.", CliFailure.USAGE);
            }
            JsonNode item = root.workItem(context, work);
            String workId = item == null ? work.strip() : text(item, "id");
            String entityId = entity != null && !entity.isBlank() ? entity.strip()
                    : item == null ? null : text(item, "qualifiedId");
            if (chosenKind == null) {
                chosenKind = item != null && "EPIC".equals(text(item, "archetype")) ? "EPIC" : "TICKET";
            }
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("repositoryId", repositoryId);
            body.put("workId", workId);
            if (entityId != null && !entityId.isEmpty()) {
                body.put("entityId", entityId);
            }
            body.put("kind", chosenKind);
            body.put("admin", admin);
            if (chosenHarness != null) {
                body.put("harness", chosenHarness);
            }
            if (instruction != null && !instruction.isBlank()) {
                body.put("instruction", instruction);
            }
            JsonNode answer = root.api(context).createAgent(body);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            String agentId = SafeText.line(text(answer.path("agent"), "agentId"));
            context.out().println(answer.path("fresh").asBoolean(true) ? "Created agent " + agentId + "."
                    : "The item already has an active agent: " + agentId + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "discard", mixinStandardHelpOptions = true,
            description = {"Remove an agent: its worktrees, credential and draft go. Discard on a slot's or an item's "
                    + "agent page; --force is Discard anyway.",
                    "POST /workspaces/api/agents/{id}/discard with {\"force\": false|true}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents discard 00000000-0000-4000-8000-0000000000a1",
                    "  qits workspaces agents discard 00000000-0000-4000-8000-0000000000a1 --force",
                    "",
                    "- Without --force the service refuses (HTTP 409) an agent whose worktrees hold work that never "
                            + "reached the git host (AGENT_HAS_LEFTOVER_WORK), whose daemon does not answer "
                            + "(AGENT_DAEMON_UNREACHABLE) or that is still busy (AGENT_BUSY). --force removes it "
                            + "anyway and throws the leftover work away."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DiscardCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Option(names = "--force", description = "Remove it despite leftover work or a daemon that does "
                + "not answer.")
        boolean force;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = agents.parent;
            boolean json = root.json();
            String id = agentId(agent);
            JsonNode answer = root.api(context).discardAgent(id, force);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Agent " + SafeText.line(id) + ": " + cell(answer, "state", 20) + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "prompt", mixinStandardHelpOptions = true,
            description = {"Send a prompt to a work item's agent: said at its next turn, as its first turn, or to a "
                    + "yielded agent that resumes to hear it. An item with no agent is told so, and nothing is "
                    + "created. Send on an agent's Chat tab.",
                    "POST /workspaces/api/agent-dispatches/delivery with {workId, text, compactFirst: false}. "
                            + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents prompt qits-123 'The ticket moved to REVIEW.'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class PromptCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(index = "0", paramLabel = "<work>",
                description = "The work item whose agent hears it: its qualified id (qits-123) or its id.")
        String work;

        @CommandLine.Parameters(index = "1", paramLabel = "<text>", description = "The prompt.")
        String text;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = agents.parent;
            boolean json = root.json();
            String prompt = required(text, "The prompt is empty. Say something.");
            String workId = root.workId(context, work);
            JsonNode answer = root.api(context).deliver(workId, prompt);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            String agentId = text(answer, "agentId");
            String outcome = agentId.isEmpty() ? "No agent works on the item; nothing was said."
                    : answer.path("delivered").asBoolean() ? "Delivered to agent " + SafeText.line(agentId)
                    + ": said at its next turn."
                    : answer.path("launched").asBoolean() ? "Agent " + SafeText.line(agentId)
                    + " hears it as its first turn, once it starts."
                    : answer.path("resumed").asBoolean() ? "Agent " + SafeText.line(agentId) + " resumes to hear it."
                    : "Agent " + SafeText.line(agentId) + " was not told.";
            context.out().println(outcome);
            if (!text(answer, "detail").isEmpty()) {
                context.out().println(SafeText.line(text(answer, "detail")));
            }
            return 0;
        }
    }

    // --- the prompt draft ---

    @CommandLine.Command(name = "draft", mixinStandardHelpOptions = true,
            subcommands = {DraftShowCommand.class, DraftSaveCommand.class, DraftDiscardCommand.class},
            description = {"An agent's saved prompt draft: what the prompt box on its Chat tab holds, kept on the "
                    + "service so it survives a reload and another device."})
    public static class DraftCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "show", mixinStandardHelpOptions = true,
            description = {"The agent's saved prompt draft: its text and when it was written. An agent with none "
                    + "says so.",
                    "GET /workspaces/api/agents/{id}/prompt-draft (HTTP 404: nothing was composed). " + Doors.READ},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents draft show 00000000-0000-4000-8000-0000000000a1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class DraftShowCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DraftCommand draft;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = draft.agents.parent;
            boolean json = root.json();
            String id = agentId(agent);
            JsonNode answer;
            try {
                answer = root.api(context).draft(id);
            } catch (CliFailure failure) {
                if (failure.status() != 404) {
                    throw failure;
                }
                if (json) {
                    Json.print(context.out(), JsonNodeFactory.instance.objectNode().putNull("draft"));
                } else {
                    context.out().println("Agent " + SafeText.line(id) + " has no saved draft.");
                }
                return 0;
            }
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            JsonNode saved = answer.path("draft");
            JsonNode content = Json.parse(text(saved, "content"));
            String written = content == null ? "" : text(content, "text");
            context.out().println("Draft of agent " + SafeText.line(id) + ", written "
                    + Table.time(text(saved, "updatedAt")) + ":");
            for (String line : written.split("\n", -1)) {
                context.out().println("  " + SafeText.line(line));
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "save", mixinStandardHelpOptions = true,
            description = {"Save the agent's prompt draft, as typing in its prompt box does. The draft holds the text "
                    + "and no picked file ranges.",
                    "PUT /workspaces/api/agents/{id}/prompt-draft with {content: '{\"text\":<text>,\"picked\":[]}', "
                            + "serializedPrompt: <text, trimmed>}. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents draft save 00000000-0000-4000-8000-0000000000a1 'Check the rounding.'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DraftSaveCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DraftCommand draft;

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<text>", description = "The draft's text.")
        String text;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = draft.agents.parent;
            boolean json = root.json();
            String id = agentId(agent);
            String written = text == null ? "" : text;
            JsonNode answer = root.api(context).saveDraft(id, body(written));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Saved the draft of agent " + SafeText.line(id) + ", "
                    + Table.time(text(answer.path("draft"), "updatedAt")) + ".");
            return 0;
        }

        /** The landing app's draft: its own JSON, {@code {text, picked}}, and the prompt it serializes to. */
        static ObjectNode body(String text) {
            ObjectNode content = JsonNodeFactory.instance.objectNode();
            content.put("text", text);
            content.putArray("picked");
            ObjectNode body = JsonNodeFactory.instance.objectNode();
            body.put("content", content.toString());
            body.put("serializedPrompt", text.strip());
            return body;
        }
    }

    @CommandLine.Command(name = "discard", mixinStandardHelpOptions = true,
            description = {"Delete the agent's saved prompt draft: Discard on a restored draft.",
                    "DELETE /workspaces/api/agents/{id}/prompt-draft. " + Doors.WRITE},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents draft discard 00000000-0000-4000-8000-0000000000a1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class DraftDiscardCommand extends PlatformCommand {

        @CommandLine.ParentCommand
        DraftCommand draft;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            WorkspacesCommand root = draft.agents.parent;
            boolean json = root.json();
            String id = agentId(agent);
            root.api(context).deleteDraft(id);
            if (!json) {
                context.out().println("Discarded the draft of agent " + SafeText.line(id) + ".");
            }
            return 0;
        }
    }

    // --- the agent's container, through the proxy ---

    /** The commands that read an agent's container: the slot it is in, and the agent. */
    abstract static class InContainer extends PlatformCommand {

        @CommandLine.Option(names = "--workspace", paramLabel = "<n>", required = true,
                description = "The agent's slot: its number, as `qits workspaces agents list` shows it.")
        Long workspace;

        abstract WorkspacesCommand root();

        /**
         * The command the harness of {@code agentId} last ran: {@code --command} when given, else the
         * agent's worktree's, as the Chat tab finds it.
         */
        String harnessCommand(WorkspacesApi api, long slot, String agentId, String given)
                throws CliFailure, InterruptedException {
            if (given != null && !given.isBlank()) {
                return given.strip();
            }
            String commandId;
            try {
                commandId = text(api.agentWorktree(slot, agentId), "commandId");
            } catch (CliFailure failure) {
                if (failure.status() == 404) {
                    throw new CliFailure("The daemon of slot " + slot + " has not started agent "
                            + SafeText.line(agentId) + " since it came up: it has no harness command.",
                            CliFailure.FAILED);
                }
                throw failure;
            }
            if (commandId.isEmpty()) {
                throw new CliFailure("Agent " + SafeText.line(agentId) + " has no harness command on slot " + slot
                        + ".", CliFailure.FAILED);
            }
            return commandId;
        }
    }

    @CommandLine.Command(name = "chat", mixinStandardHelpOptions = true,
            description = {"Read the agent's conversation: its harness command's transcript, in order. The agent's "
                    + "Chat tab, without the live socket.",
                    "The command is the agent's last harness command (GET /workspaces/container/{slot}/agent-worktrees/"
                            + "{agentId}), unless --command names one; then GET /workspaces/container/{slot}/commands/"
                            + "{commandId}/log?channel=TRANSCRIPT. With -o json, the transcript as the daemon answers it. "
                            + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents chat 00000000-0000-4000-8000-0000000000a1 --workspace 1",
                    "  qits workspaces agents chat 00000000-0000-4000-8000-0000000000a1 --workspace 1 -o json"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused, cannot be reached, or the agent has no harness "
                    + "command.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class ChatCommand extends InContainer {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Option(names = "--command", paramLabel = "<id>",
                description = "The harness command whose transcript to read. Default: the agent's last.")
        String command;

        @Override
        WorkspacesCommand root() {
            return agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            String id = agentId(agent);
            long slot = slot(workspace);
            WorkspacesApi api = root().api(context);
            String commandId = harnessCommand(api, slot, id, command);
            JsonNode answer = api.transcript(slot, commandId);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<JsonNode> lines = new ArrayList<>(entries(answer, "lines", null));
            lines.sort(Comparator.comparingLong(line -> line.path("sequence").asLong()));
            if (lines.isEmpty()) {
                context.out().println("The conversation is empty.");
                return 0;
            }
            for (JsonNode line : lines) {
                String said = turn(text(line, "content"));
                if (!said.isEmpty()) {
                    context.out().println(said);
                }
            }
            return 0;
        }

        /**
         * One transcript line for a person: who said it and its text, the tools it used named. A line
         * that is not the harness's JSON is shown as it is; a line with nothing to say gives nothing.
         */
        static String turn(String content) {
            JsonNode line = Json.parse(content);
            if (line == null || !line.isObject()) {
                return SafeText.line(content);
            }
            String type = text(line, "type");
            if (!type.equals("user") && !type.equals("assistant")) {
                return type.equals("result") ? "result: " + SafeText.line(text(line, "result")) : "";
            }
            JsonNode said = line.path("message").path("content");
            List<String> parts = new ArrayList<>();
            if (said.isTextual()) {
                parts.add(said.asText());
            }
            for (JsonNode part : said.isArray() ? said : JsonNodeFactory.instance.arrayNode()) {
                switch (text(part, "type")) {
                    case "text" -> parts.add(text(part, "text"));
                    case "tool_use" -> parts.add("[tool " + text(part, "name") + "]");
                    case "tool_result" -> parts.add("[tool result]");
                    default -> {
                        // Thinking and the kinds a newer harness invents stay out of the summary.
                    }
                }
            }
            String text = SafeText.line(String.join(" ", parts)).strip();
            return text.isEmpty() ? "" : type + ": " + text;
        }
    }

    @CommandLine.Command(name = "terminate", mixinStandardHelpOptions = true,
            description = {"End the agent's running harness command: Terminate on a live chat.",
                    "The command is the agent's last harness command (GET /workspaces/container/{slot}/agent-worktrees/"
                            + "{agentId}), unless --command names one; then POST /workspaces/container/{slot}/"
                            + "commands/{commandId}/terminate with {}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents terminate 00000000-0000-4000-8000-0000000000a1 --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused, cannot be reached, or the agent has no harness "
                    + "command.", HelpText.USAGE})
    public static class TerminateCommand extends InContainer {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Option(names = "--command", paramLabel = "<id>",
                description = "The command to end. Default: the agent's last harness command.")
        String command;

        @Override
        WorkspacesCommand root() {
            return agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            String id = agentId(agent);
            long slot = slot(workspace);
            WorkspacesApi api = root().api(context);
            String commandId = harnessCommand(api, slot, id, command);
            JsonNode answer = api.terminate(slot, commandId);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            context.out().println("Asked the daemon to end command " + SafeText.line(commandId) + ".");
            return 0;
        }
    }

    @CommandLine.Command(name = "refine", mixinStandardHelpOptions = true,
            description = {"Rewrite rough notes into a prompt, in the slot's container: Refine in the prompt box. "
                    + "Prints the rewrite; nothing is sent to an agent.",
                    "POST /workspaces/container/{slot}/prompt-refinements with {transcript, preamble?}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents refine --workspace 1 --preamble 'Fix the login' "
                    + "'uh so the login fails when the name has spaces'"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class RefineCommand extends InContainer {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<notes>", description = "The rough notes.")
        String notes;

        @CommandLine.Option(names = "--preamble", paramLabel = "<text>",
                description = "What the prompt is about, which the rewrite keeps in front.")
        String preamble;

        @Override
        WorkspacesCommand root() {
            return agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String transcript = required(notes, "The notes are empty. Give the notes to rewrite.");
            String lead = preamble == null || preamble.isBlank() ? null : preamble;
            JsonNode answer = root().api(context).refine(slot, transcript, lead);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            for (String line : text(answer, "prompt").split("\n", -1)) {
                context.out().println(SafeText.line(line));
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "harness", mixinStandardHelpOptions = true,
            description = {"The agent's harness on its slot's daemon: its last command, whether it still runs, and as "
                    + "a terminal or a chat. What the Terminal and Chat tabs attach to.",
                    "With --attach, a harness that runs as a terminal is attached to at once, as `qits workspaces "
                            + "attach <command> --workspace <n>` does (Ctrl-] detaches); a chat is refused, since "
                            + "`qits workspaces agents chat` reads it.",
                    "GET /workspaces/container/{slot}/agent-worktrees/{agentId}, then GET /workspaces/container/{slot}/"
                            + "commands. With -o json, {worktree, command}: the first answer and the command's entry "
                            + "of the second. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents harness 00000000-0000-4000-8000-0000000000a1 --workspace 1",
                    "  qits workspaces agents harness 00000000-0000-4000-8000-0000000000a1 --workspace 1 --attach",
                    "",
                    "- --attach needs a terminal for typing, and the MCP service refuses it; without a terminal it "
                            + "streams what the harness prints, read-only."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Done; with --attach, the command ended or you detached.",
                    "1:The platform refused or cannot be reached; with --attach, the agent has no terminal to "
                            + "attach to, or the connection could not be kept.",
                    HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class HarnessCommand extends InContainer {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Option(names = "--attach",
                description = "Attach to the harness's terminal once it is found, until it ends or Ctrl-] detaches.")
        boolean attach;

        /** The caller's terminal, for --attach; a test hands its own. */
        LocalTerminal.Factory terminals = LocalTerminal::of;

        @Override
        WorkspacesCommand root() {
            return agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            String id = agentId(agent);
            long slot = slot(workspace);
            if (attach) {
                WorkspacesCommand.refuseWithoutATerminal();
            }
            WorkspacesApi api = root().api(context);
            JsonNode worktree;
            try {
                worktree = api.agentWorktree(slot, id);
            } catch (CliFailure failure) {
                if (failure.status() != 404) {
                    throw failure;
                }
                worktree = null;
            }
            String commandId = worktree == null ? "" : text(worktree, "commandId");
            JsonNode command = null;
            if (!commandId.isEmpty()) {
                for (JsonNode entry : entries(api.commands(slot), "entries", "command")) {
                    if (commandId.equals(text(entry, "id"))) {
                        command = entry;
                    }
                }
            }
            if (attach) {
                return attachTo(context, api, slot, id, commandId, command);
            }
            if (json) {
                ObjectNode both = JsonNodeFactory.instance.objectNode();
                both.set("worktree", worktree);
                both.set("command", command);
                Json.print(context.out(), both);
                return 0;
            }
            PrintStream out = context.out();
            if (worktree == null) {
                out.println("The daemon of slot " + slot + " has not started agent " + SafeText.line(id)
                        + " since it came up.");
            } else if (command == null) {
                out.println("Agent " + SafeText.line(id) + " has no harness command running on slot " + slot + ".");
            } else {
                out.println("Agent " + SafeText.line(id) + ": command " + SafeText.line(commandId) + ", "
                        + ("TERMINAL".equals(text(command, "kind")) ? "a terminal" : "a chat") + ", "
                        + SafeText.line(text(command, "status")) + ".");
            }
            return 0;
        }

        /** --attach: the harness's command, when it is a terminal. What was found is said on stderr. */
        private int attachTo(CliContext context, WorkspacesApi api, long slot, String id, String commandId,
                             JsonNode command) throws CliFailure, InterruptedException {
            if (command == null) {
                throw new CliFailure("Agent " + SafeText.line(id) + " has no harness command on slot " + slot
                        + " to attach to.", CliFailure.FAILED);
            }
            if (!"TERMINAL".equals(text(command, "kind"))) {
                throw new CliFailure("Agent " + SafeText.line(id) + "'s harness runs as a chat, not a terminal: read it "
                        + "with `qits workspaces agents chat " + SafeText.line(id) + " --workspace " + slot + "`.",
                        CliFailure.FAILED);
            }
            context.err().println("Agent " + SafeText.line(id) + ": attaching to command " + SafeText.line(commandId)
                    + " on slot " + slot + ".");
            return WorkspacesCommand.attach(context, api, slot, commandId, terminals);
        }
    }

    // --- files ---

    @CommandLine.Command(name = "files", mixinStandardHelpOptions = true,
            subcommands = {FilesListCommand.class, FilesReadCommand.class, FilesDetectCommand.class},
            description = {"The agent's worktree, as its Files tab shows it: the tree, one file, and the frameworks "
                    + "and test links the tab draws."})
    public static class FilesCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The agent's worktree: without --path the whole tree, every gitignored directory a stub "
                    + "with its entry count; with --path that directory, one level deep.",
                    "GET /workspaces/container/{slot}/agent-worktrees/{agentId}/files[?path=]. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents files list 00000000-0000-4000-8000-0000000000a1 --workspace 1",
                    "  qits workspaces agents files list 00000000-0000-4000-8000-0000000000a1 --workspace 1 "
                            + "--path node_modules"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesListCommand extends InContainer {

        @CommandLine.ParentCommand
        FilesCommand files;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Option(names = "--path", paramLabel = "<dir>",
                description = "A directory of the worktree, relative to its root. Default: the whole tree.")
        String path;

        @Override
        WorkspacesCommand root() {
            return files.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            String id = agentId(agent);
            long slot = slot(workspace);
            String dir = path == null || path.isBlank() ? null : path.strip();
            JsonNode answer = root().api(context).files(slot, id, dir);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            for (JsonNode file : answer.path("paths")) {
                context.out().println(SafeText.line(file.asText()));
            }
            for (JsonNode lazy : answer.path("lazyDirs")) {
                context.out().println(SafeText.line(text(lazy, "path")) + "/  (" + lazy.path("childCount").asInt()
                        + " entries, not listed: --path " + SafeText.line(text(lazy, "path")) + ")");
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "read", mixinStandardHelpOptions = true,
            description = {"One file of the agent's worktree, its text. A binary file, or one over the daemon's 2 MB "
                    + "cap, is not shown.",
                    "GET /workspaces/container/{slot}/agent-worktrees/{agentId}/files/content?path=. In the table form "
                            + "each line is made safe for a terminal (a tab becomes a space); -o json keeps the text "
                            + "exact. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents files read 00000000-0000-4000-8000-0000000000a1 "
                    + "src/main/java/app/Login.java --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, "1:The platform refused (HTTP 404: the path names nothing), or cannot be "
                    + "reached.", HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesReadCommand extends InContainer {

        @CommandLine.ParentCommand
        FilesCommand files;

        @CommandLine.Parameters(index = "0", paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @CommandLine.Parameters(index = "1", paramLabel = "<path>",
                description = "The file, relative to the worktree's root.")
        String path;

        @Override
        WorkspacesCommand root() {
            return files.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            String id = agentId(agent);
            long slot = slot(workspace);
            String file = required(path, "Name the file: its path in the worktree.");
            JsonNode answer = root().api(context).file(slot, id, file);
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            if (answer.path("binary").asBoolean(false)) {
                context.out().println(SafeText.line(file) + " is binary, or over the daemon's 2 MB cap: not shown.");
                return 0;
            }
            for (String line : text(answer, "content").split("\n", -1)) {
                context.out().println(SafeText.line(line));
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "detect", mixinStandardHelpOptions = true,
            description = {"The frameworks the agent's worktree holds (each with its member paths) and which tests "
                    + "reach each source file: the Files tab's footer and its test links.",
                    "GET /workspaces/container/{slot}/agent-worktrees/{agentId}/detection. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents files detect 00000000-0000-4000-8000-0000000000a1 --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class FilesDetectCommand extends InContainer {

        @CommandLine.ParentCommand
        FilesCommand files;

        @CommandLine.Parameters(paramLabel = "<agent>", description = "The agent's id.")
        String agent;

        @Override
        WorkspacesCommand root() {
            return files.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            JsonNode answer = root().api(context).detect(slot(workspace), agentId(agent));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode framework : answer.path("frameworks")) {
                rows.add(List.of(cell(framework, "frameworkId", 30), cell(framework, "root", 60),
                        cell(framework, "label", 40), String.valueOf(framework.path("memberPaths").size())));
            }
            if (rows.isEmpty()) {
                context.out().println("No frameworks.");
            } else {
                Table.print(context.out(), "", List.of("FRAMEWORK", "ROOT", "LABEL", "FILES"), rows);
            }
            for (JsonNode link : answer.path("links")) {
                List<String> tests = new ArrayList<>();
                for (JsonNode test : link.path("tests")) {
                    tests.add(SafeText.line(text(test, "path")));
                }
                context.out().println(SafeText.line(text(link, "path")) + "  <-  " + String.join(", ", tests));
            }
            return 0;
        }
    }

    // --- sign-in ---

    @CommandLine.Command(name = "sign-in", mixinStandardHelpOptions = true,
            subcommands = {SignInStatusCommand.class, SignInOpenCommand.class},
            description = {"Whether anybody signed the slot's harnesses in, on the credential volume every container "
                    + "shares, and the sign-in terminal that does it: the notice on an agent's Terminal tab."})
    public static class SignInCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    @CommandLine.Command(name = "status", mixinStandardHelpOptions = true,
            description = {"Each harness of the slot's daemon, whether it is signed in, and the daemon's hint when it "
                    + "is not.",
                    "GET /workspaces/container/{slot}/agents/available. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents sign-in status --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class SignInStatusCommand extends InContainer {

        @CommandLine.ParentCommand
        SignInCommand signIn;

        @Override
        WorkspacesCommand root() {
            return signIn.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            JsonNode answer = root().api(context).signInReport(slot(workspace));
            if (json) {
                Json.print(context.out(), answer);
                return 0;
            }
            List<List<String>> rows = new ArrayList<>();
            for (JsonNode capability : answer.path("capabilities")) {
                rows.add(List.of(cell(capability, "harness", 12),
                        capability.path("authenticated").asBoolean(false) ? "yes" : "no",
                        cell(capability, "authDetail", 160)));
            }
            if (rows.isEmpty()) {
                context.out().println("The daemon reports no harness.");
                return 0;
            }
            Table.print(context.out(), "", List.of("HARNESS", "SIGNED IN", "DETAIL"), rows);
            return 0;
        }
    }

    @CommandLine.Command(name = "open", mixinStandardHelpOptions = true,
            description = {"Start a harness's sign-in in the slot's container: Open the sign-in terminal. It is an "
                    + "interactive terminal: --attach attaches this one to it at once, or `qits workspaces attach "
                    + "<command> --workspace <n>` later, with the command id this prints. The agent's Terminal tab "
                    + "in the landing app shows the same terminal.",
                    "POST /workspaces/container/{slot}/agents/sign-in with {agentType} for --harness, else {}; the "
                            + "answer's command.id is the terminal's command. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents sign-in open --workspace 1 --harness CLAUDE --attach",
                    "  qits workspaces agents sign-in open --workspace 1 --harness CLAUDE",
                    "",
                    "- With --attach, Ctrl-] detaches and leaves the sign-in running. --attach needs a terminal for "
                            + "typing, and the MCP service refuses it; without a terminal it streams, read-only."},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {"0:Done; with --attach, the sign-in ended or you detached.",
                    "1:The platform refused or cannot be reached; with --attach, the connection could not be kept.",
                    HelpText.USAGE})
    public static class SignInOpenCommand extends InContainer {

        @CommandLine.ParentCommand
        SignInCommand signIn;

        @CommandLine.Option(names = "--harness", paramLabel = "CLAUDE|KIMI",
                description = "The harness to sign in. Default: the daemon's.")
        String harness;

        @CommandLine.Option(names = "--attach",
                description = "Attach to the sign-in terminal once it is open, until it ends or Ctrl-] detaches.")
        boolean attach;

        /** The caller's terminal, for --attach; a test hands its own. */
        LocalTerminal.Factory terminals = LocalTerminal::of;

        @Override
        WorkspacesCommand root() {
            return signIn.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String chosen = harness(harness);
            if (attach) {
                // Before anything opens: a sign-in nobody can reach would only wait in the container.
                WorkspacesCommand.refuseWithoutATerminal();
            }
            WorkspacesApi api = root().api(context);
            JsonNode answer = api.openSignIn(slot, chosen);
            String commandId = text(answer.path("command"), "id");
            if (json) {
                Json.print(context.out(), answer);
            }
            if (!attach) {
                if (!json) {
                    context.out().println("Opened the sign-in terminal on slot " + slot + ": command "
                            + SafeText.line(commandId) + ". Attach to it with `qits workspaces attach "
                            + SafeText.line(commandId) + " --workspace " + slot + "`.");
                }
                return 0;
            }
            if (commandId.isEmpty()) {
                throw new CliFailure("The daemon of slot " + slot + " opened the sign-in without naming its command, "
                        + "so there is nothing to attach to.", CliFailure.FAILED);
            }
            // stdout is the terminal from here on; what was opened is said beside it.
            context.err().println("Opened the sign-in terminal on slot " + slot + ": command "
                    + SafeText.line(commandId) + ". Attaching.");
            return WorkspacesCommand.attach(context, api, slot, commandId, terminals);
        }
    }

    // --- plugins ---

    @CommandLine.Command(name = "plugins", mixinStandardHelpOptions = true,
            subcommands = {PluginsListCommand.class, PluginsInstallCommand.class},
            description = {"The plugins on the agent home every container of the slot's daemon shares: the plugins "
                    + "section of an agent's Terminal tab."})
    public static class PluginsCommand implements Runnable {

        @CommandLine.ParentCommand
        WorkspaceAgentsCommand agents;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public void run() {
            throw new CommandLine.ParameterException(spec.commandLine(), "Name a command.");
        }
    }

    static void printPlugins(PrintStream out, JsonNode answer) {
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode plugin : answer.path("installed")) {
            rows.add(List.of(cell(plugin, "pluginId", 80), plugin.path("enabled").asBoolean(false) ? "yes" : "no"));
        }
        if (rows.isEmpty()) {
            out.println("No plugins installed.");
            return;
        }
        Table.print(out, "", List.of("PLUGIN", "ENABLED"), rows);
    }

    @CommandLine.Command(name = "list", mixinStandardHelpOptions = true,
            description = {"The plugins installed, each marketplace-qualified, and whether it is switched on.",
                    "GET /workspaces/container/{slot}/agent-plugins. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents plugins list --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    @TuiCommand(effect = Effect.READ)
    public static class PluginsListCommand extends InContainer {

        @CommandLine.ParentCommand
        PluginsCommand plugins;

        @Override
        WorkspacesCommand root() {
            return plugins.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            JsonNode answer = root().api(context).plugins(slot(workspace));
            if (json) {
                Json.print(context.out(), answer);
            } else {
                printPlugins(context.out(), answer);
            }
            return 0;
        }
    }

    @CommandLine.Command(name = "install", mixinStandardHelpOptions = true,
            description = {"Install a plugin on the shared agent home: Install on a plugin not installed yet. Prints "
                    + "the installed set as it now is.",
                    "POST /workspaces/container/{slot}/agent-plugins/{pluginId}/install with {}. " + Doors.PROXY},
            footerHeading = HelpText.EXAMPLES,
            footer = {"  qits workspaces agents plugins install pyright-lsp --workspace 1"},
            exitCodeListHeading = HelpText.EXIT_CODES,
            exitCodeList = {HelpText.DONE, HelpText.REFUSED, HelpText.USAGE})
    public static class PluginsInstallCommand extends InContainer {

        @CommandLine.ParentCommand
        PluginsCommand plugins;

        @CommandLine.Parameters(paramLabel = "<plugin>", description = "The plugin's id, as the catalogue names it.")
        String plugin;

        @Override
        WorkspacesCommand root() {
            return plugins.agents.parent;
        }

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            boolean json = root().json();
            long slot = slot(workspace);
            String id = required(plugin, "Name the plugin: its id.");
            JsonNode answer = root().api(context).installPlugin(slot, id);
            if (json) {
                Json.print(context.out(), answer);
            } else {
                printPlugins(context.out(), answer);
            }
            return 0;
        }
    }
}
