package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * <b>What {@code qits workspaces agents} asks a slot's daemon, and why</b> (ticket qits-1149): the
 * doors qits-workspace-daemon answers through qits-workspaces' container proxy, {@code
 * /workspaces/container/{slot}/…}. The daemon's golden masters record the paths with that prefix,
 * as the landing app's tabs call them, so the pact names the daemon as the provider.
 * <p>
 * The Chat and Terminate commands find the harness's command through the agent's worktree first
 * when no {@code --command} is given, so each has a row for that read beside its own call.
 */
public final class WorkspaceDaemonContract {

    static final String HARNESS_NOBODY_SIGNED_IN = "a workspace daemon with a harness nobody signed in";
    static final String SIGNED_IN_HARNESS = "a workspace daemon with a signed-in harness";
    static final String AGENT_CHATTING = "a workspace daemon with an agent chatting";
    static final String AGENT_RUNNING = "a workspace daemon with an agent running";
    static final String TAKEN_TURNS = "a workspace daemon with an agent that has taken turns";
    static final String IGNORED_DIRECTORY = "a workspace daemon with an agent whose worktree has an ignored directory";
    static final String HOLDS_FILES = "a workspace daemon with an agent whose worktree holds files";
    static final String PLUGINS_INSTALLED = "a workspace daemon with plugins installed";

    private static final CliPact BASE = CliPact.of("qits-workspace-daemon", "qits-workspace-daemon");

    @FunctionalInterface
    interface Call {
        void run(WorkspacesApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new WorkspacesApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static long slot(Map<String, String> params) {
        return Long.parseLong(params.get("workspaceRowId"));
    }

    private static String query(Operation recorded, String name) {
        List<String> values = recorded.query().get(name);
        return values == null ? null : recorded.example(values.getFirst());
    }

    /** The segment after {@code marker} in the recorded path. */
    private static String segmentAfter(Operation recorded, String marker) {
        String path = recorded.examplePath();
        String rest = path.substring(path.indexOf(marker) + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    // --- the calls ------------------------------------------------------------------------------

    /** The harness's command, from the agent's worktree, as harness, chat and terminate find it. */
    private static final Call WORKTREE = (api, params, recorded) ->
            assertThat(api.agentWorktree(slot(params), params.get("agentId")).path("commandId").asText())
                    .isEqualTo(params.get("commandId"));

    /** A daemon that has not started the agent since it came up answers 404: harness says so. */
    private static final Call NO_WORKTREE = (api, params, recorded) -> {
        CliFailure refused = catchThrowableOfType(CliFailure.class,
                () -> api.agentWorktree(slot(params), params.get("agentId")));
        assertThat(refused).isNotNull();
        assertThat(refused.status()).isEqualTo(404);
    };

    private static final Call COMMANDS = (api, params, recorded) ->
            assertThat(WorkspacesCommand.entries(api.commands(slot(params)), "entries", "command")).isNotEmpty()
                    .allSatisfy(c -> {
                        assertThat(c.path("id").asText()).isNotBlank();
                        assertThat(c.path("kind").asText()).isNotBlank();
                        assertThat(c.path("status").asText()).isNotBlank();
                    });

    private static final Call TRANSCRIPT = (api, params, recorded) ->
            assertThat(api.transcript(slot(params), params.get("commandId")).path("lines")).isNotEmpty()
                    .allSatisfy(l -> assertThat(l.path("sequence").isNumber()).isTrue());

    /** The command prints only that it asked; the answer is the -o json form's. */
    private static final Call TERMINATE = (api, params, recorded) ->
            api.terminate(slot(params), params.get("commandId"));

    private static final Call REFINE = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        assertThat(api.refine(slot(params), sent.path("transcript").asText(), sent.path("preamble").asText())
                .path("prompt").asText()).isNotBlank();
    };

    private static final Call FILES = (api, params, recorded) -> {
        JsonNode answer = api.files(slot(params), params.get("agentId"), query(recorded, "path"));
        assertThat(answer.path("paths").isArray()).isTrue();
        answer.path("lazyDirs").forEach(d -> assertThat(d.path("childCount").isNumber()).isTrue());
    };

    private static final Call FILE = (api, params, recorded) -> {
        JsonNode answer = api.file(slot(params), params.get("agentId"), query(recorded, "path"));
        assertThat(answer.path("binary").isBoolean()).isTrue();
        assertThat(answer.path("content").isTextual()).isTrue();
    };

    /** A path the daemon does not have: its 404, the message on stderr. */
    private static final Call NO_FILE = (api, params, recorded) -> {
        CliFailure refused = catchThrowableOfType(CliFailure.class,
                () -> api.file(slot(params), params.get("agentId"), query(recorded, "path")));
        assertThat(refused).isNotNull();
        assertThat(refused.status()).isEqualTo(404);
    };

    private static final Call DETECT = (api, params, recorded) -> {
        JsonNode answer = api.detect(slot(params), params.get("agentId"));
        assertThat(answer.path("frameworks")).allSatisfy(f -> assertThat(f.path("frameworkId").asText()).isNotBlank());
        assertThat(answer.path("links")).allSatisfy(l -> assertThat(l.path("tests").isArray()).isTrue());
    };

    private static final Call SIGN_IN_REPORT = (api, params, recorded) ->
            assertThat(api.signInReport(slot(params)).path("capabilities")).isNotEmpty()
                    .allSatisfy(c -> assertThat(c.path("authenticated").isBoolean()).isTrue());

    private static final Call OPEN_SIGN_IN = (api, params, recorded) ->
            assertThat(api.openSignIn(slot(params), CliRow.exampleBody(recorded).path("agentType").asText())
                    .path("command").path("id").asText()).isNotBlank();

    private static final Call PLUGINS = (api, params, recorded) ->
            assertThat(api.plugins(slot(params)).path("installed")).isNotEmpty()
                    .allSatisfy(p -> assertThat(p.path("enabled").isBoolean()).isTrue());

    private static final Call INSTALL = (api, params, recorded) ->
            assertThat(api.installPlugin(slot(params), segmentAfter(recorded, "/agent-plugins/")).path("installed"))
                    .isNotEmpty();

    // --- the table ------------------------------------------------------------------------------

    public static final CliPact CONTRACT = BASE
            .client(WorkspacesApi.class)
            .excluded("WorkspacesApi#terminalSocket", "qits workspaces attach: the daemon's terminal, a WebSocket")
            .rows(rows());

    private static List<CliRow> rows() {
        List<CliRow> rows = new ArrayList<>();
        rows.add(row("qits workspaces agents harness", AGENT_RUNNING, "getAgentWorktree", "agentWorktree", WORKTREE)
                .consumes("commandId"));
        rows.add(row("qits workspaces agents harness", SIGNED_IN_HARNESS, "getAgentWorktree", "agentWorktree",
                NO_WORKTREE));
        for (String state : List.of(AGENT_RUNNING, AGENT_CHATTING)) {
            rows.add(row("qits workspaces agents harness", state, "listCommands", "commands", COMMANDS)
                    .consumes("entries[].command.id", "entries[].command.kind", "entries[].command.status"));
        }
        rows.add(row("qits workspaces agents chat", AGENT_RUNNING, "getAgentWorktree", "agentWorktree", WORKTREE)
                .consumes("commandId"));
        rows.add(row("qits workspaces agents chat", TAKEN_TURNS, "getCommandLog", "transcript", TRANSCRIPT)
                .consumes("lines[].sequence", "lines[].content"));
        rows.add(row("qits workspaces agents terminate", AGENT_RUNNING, "getAgentWorktree", "agentWorktree", WORKTREE)
                .consumes("commandId"));
        rows.add(row("qits workspaces agents terminate", AGENT_CHATTING, "terminateCommand", "terminate", TERMINATE));
        rows.add(row("qits workspaces agents refine", SIGNED_IN_HARNESS, "refinePrompt", "refine", REFINE)
                .consumes("prompt"));
        for (String state : List.of(HOLDS_FILES, IGNORED_DIRECTORY)) {
            rows.add(row("qits workspaces agents files list", state, "listFiles", "files", FILES)
                    .consumes("paths", "lazyDirs[].path", "lazyDirs[].childCount"));
        }
        rows.add(row("qits workspaces agents files read", HOLDS_FILES, "readFile", "file", FILE)
                .consumes("binary", "content"));
        rows.add(row("qits workspaces agents files read", IGNORED_DIRECTORY, "readFile", "file", NO_FILE)
                .consumes("message"));
        rows.add(row("qits workspaces agents files detect", HOLDS_FILES, "detect", "detect", DETECT)
                .consumes("frameworks[].frameworkId", "frameworks[].root", "frameworks[].label",
                        "frameworks[].memberPaths", "links[].path", "links[].tests[].path"));
        for (String state : List.of(HARNESS_NOBODY_SIGNED_IN, SIGNED_IN_HARNESS)) {
            rows.add(row("qits workspaces agents sign-in status", state, "availableAgents", "signInReport",
                    SIGN_IN_REPORT).consumes("capabilities[].harness", "capabilities[].authenticated",
                    "capabilities[].authDetail"));
        }
        rows.add(row("qits workspaces agents sign-in open", HARNESS_NOBODY_SIGNED_IN, "openSignInTerminal", "openSignIn",
                OPEN_SIGN_IN).consumes("command.id"));
        rows.add(row("qits workspaces agents plugins list", PLUGINS_INSTALLED, "listPlugins", "plugins", PLUGINS)
                .consumes("installed[].pluginId", "installed[].enabled"));
        rows.add(row("qits workspaces agents plugins install", PLUGINS_INSTALLED, "installPlugin", "installPlugin",
                INSTALL).consumes("installed[].pluginId", "installed[].enabled"));
        return List.copyOf(rows);
    }

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "WorkspacesApi#" + method, api(call));
    }

    private WorkspaceDaemonContract() {
    }
}
