package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.cli.access.waits.WaitsContract;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * <b>What {@code qits workspaces} and {@code qits wait} ask qits-workspaces, and why</b> (ticket
 * qits-1149): the JSON API under {@code /workspaces/api}, which the landing app's workspace pages
 * call the same way ({@code LandingPactTest}). The doors a slot's daemon answers through the
 * container proxy are qits-workspace-daemon's, in {@link WorkspaceDaemonContract}.
 * <p>
 * One row per (command, call), each in a state qits-workspaces records, with the request it
 * recorded: the slot and agent ids are the state's examples, the bodies the recorded ones.
 * {@code consumes} is what the table form prints; {@code -o json} prints the answer as it came. A
 * command whose options pick the query ({@code agents list}) has a row per query the provider
 * recorded. A refusal a command words itself (a draft that is not there, a 204 health) is a row too.
 */
public final class WorkspacesContract {

    static final String SLOTS_HOSTING_AGENTS = "a project with workspace slots hosting agents";
    static final String SLOT_HOSTING_AGENTS = "a workspace slot hosting agents";
    static final String WORKSPACE_STARTING = "an agent whose workspace is starting";
    static final String STOPPED_SLOT = "a stopped workspace slot";
    static final String RUNNING_SLOT = "a workspace slot running on a target";
    static final String DAEMON_NOT_CONNECTED = "an agent whose workspace daemon is not connected";
    static final String BOUND_WORKSPACES = "a project with workspaces bound to work items";
    static final String NO_OPEN_WORKSPACE = "no work item has an open workspace";
    static final String NO_WORKSPACES = "a work item with no workspaces";
    static final String AGENTS_BOUND = "a project with agents bound to work items";
    static final String WAITING_FOR_A_SLOT = "an agent waiting for a workspace slot";
    static final String WORKING_ON_A_TICKET = "an agent working on a ticket";
    static final String SAVED_DRAFT = "an agent with a saved prompt draft";
    static final String NO_DRAFT = "an agent with no prompt draft";

    private static final CliPact BASE = CliPact.of("qits-workspaces-service", "qits-workspaces");

    /** A slot as the list's row and {@code printSlot} read it. */
    static final List<String> SLOT = List.of("id", "workspaceId", "runtimeStatus", "runtimeError", "placement",
            "admin", "queuedAt", "daemonConnectedAt", "daemonVersion", "daemonOutdated");

    /** An agent as {@code agents list} prints it. */
    static final List<String> AGENT = List.of("agentId", "workspaceRowId", "entityId", "state", "runState", "harness",
            "waitingOn", "entityTitle");

    @FunctionalInterface
    interface Call {
        void run(WorkspacesApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new WorkspacesApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static JsonNode body(Operation recorded) {
        return CliRow.exampleBody(recorded);
    }

    private static String query(Operation recorded, String name) {
        List<String> values = recorded.query().get(name);
        return values == null ? null : recorded.example(values.getFirst());
    }

    private static long slot(Map<String, String> params) {
        return Long.parseLong(params.get("workspaceId"));
    }

    // --- the calls ------------------------------------------------------------------------------

    private static final Call LIST = (api, params, recorded) -> {
        List<JsonNode> slots = WorkspacesCommand.entries(api.workspaces(query(recorded, "repositoryId")), "entries",
                "workspace");
        assertThat(slots).isNotEmpty().allSatisfy(s -> assertThat(s.path("workspaceId").asText()).isNotBlank());
    };

    private static final Call SHOW = (api, params, recorded) -> {
        JsonNode answer = api.workspace(slot(params));
        assertThat(answer.path("workspace").path("runtimeStatus").asText()).isNotBlank();
    };

    private static final Call ACTIVE_PROCESS = (api, params, recorded) ->
            assertThat(api.activeProcess(slot(params)).has("technicalProcessId")).isTrue();

    private static Call verb(String verb) {
        return (api, params, recorded) -> {
            JsonNode answer = api.containerVerb(slot(params), verb);
            JsonNode slot = answer.path("workspace").isObject() ? answer.get("workspace") : answer;
            assertThat(slot.path("runtimeStatus").asText()).isNotBlank();
        };
    }

    /** {@code recreate} on a slot whose daemon is away: the service's 400, its message on stderr. */
    private static final Call RECREATE_REFUSED = (api, params, recorded) -> {
        CliFailure refused = catchThrowableOfType(CliFailure.class,
                () -> api.containerVerb(slot(params), "recreate-container"));
        assertThat(refused).isNotNull();
        assertThat(refused.status()).isEqualTo(400);
    };

    private static final Call ITEMS = (api, params, recorded) -> {
        List<JsonNode> open = WorkspacesCommand.entries(api.openWorkspaces(), "entries", "workspace");
        assertThat(open.size()).isEqualTo(BASE.masters().json(recorded.state(), recorded.operationId())
                .path("entries").size());
        open.forEach(w -> assertThat(w.path("workId").asText()).isNotBlank());
    };

    private static String workRef(Operation recorded) {
        String path = recorded.examplePath();
        return path.substring("/workspaces/api/work/".length(), path.lastIndexOf("/workspaces"));
    }

    private static final Call HISTORY = (api, params, recorded) -> {
        List<JsonNode> workspaces = WorkspacesCommand.entries(api.workItemWorkspaces(workRef(recorded)), "entries",
                "workspace");
        assertThat(workspaces.size()).isEqualTo(BASE.masters().json(recorded.state(), recorded.operationId())
                .path("entries").size());
        workspaces.forEach(w -> assertThat(w.path("status").asText()).isNotBlank());
    };

    private static final Call AGENTS = (api, params, recorded) -> {
        String slot = query(recorded, "workspaceId");
        List<JsonNode> agents = WorkspacesCommand.entries(api.agents(query(recorded, "workId"),
                slot == null ? null : Long.valueOf(slot), query(recorded, "state"), query(recorded, "projectId")),
                "agents", null);
        assertThat(agents).isNotEmpty().allSatisfy(a -> assertThat(a.path("agentId").asText()).isNotBlank());
    };

    private static final Call DISCARD = (api, params, recorded) ->
            assertThat(api.discardAgent(params.get("agentId"), body(recorded).path("force").asBoolean())
                    .path("state").asText()).isNotBlank();

    private static final Call DELIVER = (api, params, recorded) -> {
        JsonNode answer = api.deliver(body(recorded).path("workId").asText(), body(recorded).path("text").asText());
        assertThat(answer.path("delivered").isBoolean()).isTrue();
        assertThat(answer.path("detail").asText()).isNotBlank();
    };

    private static final Call DRAFT = (api, params, recorded) -> {
        JsonNode saved = api.draft(params.get("agentId")).path("draft");
        assertThat(WorkspacesCommand.Json.parse(saved.path("content").asText())).isNotNull();
        assertThat(saved.path("updatedAt").asText()).isNotBlank();
    };

    /** {@code draft show} words the 404 itself: the agent has no saved draft. */
    private static final Call NO_SAVED_DRAFT = (api, params, recorded) -> {
        CliFailure refused = catchThrowableOfType(CliFailure.class, () -> api.draft(params.get("agentId")));
        assertThat(refused).isNotNull();
        assertThat(refused.status()).isEqualTo(404);
    };

    private static final Call SAVE_DRAFT = (api, params, recorded) -> {
        JsonNode sent = WorkspaceAgentsCommand.DraftSaveCommand.body(body(recorded).path("serializedPrompt").asText());
        assertThat(sent).isEqualTo(body(recorded));
        assertThat(api.saveDraft(params.get("agentId"), sent).path("draft").path("updatedAt").asText()).isNotBlank();
    };

    private static final Call DELETE_DRAFT = (api, params, recorded) ->
            assertThat(api.deleteDraft(params.get("agentId")).isNull()).isTrue();

    // --- the table ------------------------------------------------------------------------------

    public static final CliPact CONTRACT = BASE
            .client(WorkspacesApi.class)
            .client(WaitsContract.CLIENT)
            .excluded("WorkspacesApi#processEvents", "qits workspaces log: a technical process's log, Server-Sent"
                    + " Events, not a REST call")
            .excluded("WorkspacesApi#client", "the client the doors are called with, handed to the terminal socket")
            .excluded("WorkspacesApi#uri", "builds an address under the base; every door goes through it")
            .rows(rows())
            .rows(WaitsContract.ROWS);

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        rows.add(row("qits workspaces list", SLOTS_HOSTING_AGENTS, "listWorkspaces", "workspaces", LIST)
                .consumes(held(SLOTS_HOSTING_AGENTS, "listWorkspaces", prefixed("entries[].workspace.", SLOT))));
        for (String state : List.of(SLOT_HOSTING_AGENTS, WORKSPACE_STARTING)) {
            rows.add(row("qits workspaces show", state, "getWorkspace", "workspace", SHOW)
                    .consumes(held(state, "getWorkspace", prefixed("workspace.", SLOT))));
            rows.add(row("qits workspaces active-process", state, "getWorkspaceActiveProcess", "activeProcess",
                    ACTIVE_PROCESS).consumes("technicalProcessId"));
        }
        rows.add(row("qits workspaces start", STOPPED_SLOT, "ensureWorkspaceContainer", "containerVerb",
                verb("ensure-container")).consumes("workspace.runtimeStatus"));
        rows.add(row("qits workspaces stop", RUNNING_SLOT, "stopWorkspaceContainer", "containerVerb",
                verb("stop-container")).consumes("runtimeStatus"));
        rows.add(row("qits workspaces recreate", DAEMON_NOT_CONNECTED, "recreateWorkspaceContainer", "containerVerb",
                RECREATE_REFUSED).consumes("message"));
        rows.add(row("qits workspaces items", BOUND_WORKSPACES, "listOpenWorkspaces", "openWorkspaces", ITEMS)
                .consumes("entries[].workspace.workId", "entries[].workspace.id", "entries[].workspace.branch"));
        rows.add(row("qits workspaces items", NO_OPEN_WORKSPACE, "listOpenWorkspaces", "openWorkspaces", ITEMS)
                .consumes("entries"));
        rows.add(row("qits workspaces history", BOUND_WORKSPACES, "listWorkItemWorkspaces", "workItemWorkspaces",
                HISTORY).consumes(held(BOUND_WORKSPACES, "listWorkItemWorkspaces", prefixed("entries[].workspace.",
                List.of("id", "status", "branch", "createdAt", "resolvedAt")))));
        rows.add(row("qits workspaces history", NO_WORKSPACES, "listWorkItemWorkspaces", "workItemWorkspaces",
                HISTORY).consumes("entries"));

        // qits workspaces agents: one row per query the provider recorded
        for (String state : List.of(SLOTS_HOSTING_AGENTS, AGENTS_BOUND, WORKSPACE_STARTING, SLOT_HOSTING_AGENTS)) {
            rows.add(row("qits workspaces agents list", state, "listAgents", "agents", AGENTS)
                    .consumes(held(state, "listAgents", prefixed("agents[].", AGENT))));
        }
        rows.add(row("qits workspaces agents discard", WAITING_FOR_A_SLOT, "discardAgent", "discardAgent", DISCARD)
                .consumes("state"));
        for (String state : List.of(WORKING_ON_A_TICKET, NO_WORKSPACES)) {
            rows.add(row("qits workspaces agents prompt", state, "deliverAgentTurn", "deliver", DELIVER)
                    .consumes("agentId", "delivered", "launched", "resumed", "detail"));
        }
        rows.add(row("qits workspaces agents draft show", SAVED_DRAFT, "getAgentPromptDraft", "draft", DRAFT)
                .consumes("draft.content", "draft.updatedAt"));
        rows.add(row("qits workspaces agents draft show", NO_DRAFT, "getAgentPromptDraft", "draft", NO_SAVED_DRAFT));
        rows.add(row("qits workspaces agents draft save", SAVED_DRAFT, "saveAgentPromptDraft", "saveDraft",
                SAVE_DRAFT).consumes("draft.updatedAt"));
        rows.add(row("qits workspaces agents draft discard", SAVED_DRAFT, "deleteAgentPromptDraft", "deleteDraft",
                DELETE_DRAFT));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "WorkspacesApi#" + method, api(call));
    }

    static List<String> prefixed(String prefix, List<String> paths) {
        return paths.stream().map(p -> prefix + p).toList();
    }

    private static List<String> held(String state, String operationId, List<String> paths) {
        return BASE.held(state, operationId, paths);
    }

    private WorkspacesContract() {
    }
}
