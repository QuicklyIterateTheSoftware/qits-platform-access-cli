package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits workspaces agents} asks a slot's daemon, and why</b> (ticket qits-1149): the
 * doors qits-workspace-daemon answers through qits-workspaces' container proxy, {@code
 * /workspaces/container/{slot}/…}. The daemon's golden masters record the paths with that prefix,
 * as the landing app's tabs call them, so the pact names the daemon as the provider.
 * <p>
 * The agent's own chat, files and harness doors moved to {@code qits agents}, through the agent
 * proxy (epic qits-1667), which forwards raw to the daemon and is recorded by no provider
 * ({@link WorkspacesContract}'s {@code AGENT_PROXY} exclusion); their rows here, on the container
 * proxy's slot-addressed aliases, went with those aliases.
 */
public final class WorkspaceDaemonContract {

    static final String HARNESS_NOBODY_SIGNED_IN = "a workspace daemon with a harness nobody signed in";
    static final String SIGNED_IN_HARNESS = "a workspace daemon with a signed-in harness";
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

    /** The segment after {@code marker} in the recorded path. */
    private static String segmentAfter(Operation recorded, String marker) {
        String path = recorded.examplePath();
        String rest = path.substring(path.indexOf(marker) + marker.length());
        int end = rest.indexOf('/');
        return end < 0 ? rest : rest.substring(0, end);
    }

    // --- the calls ------------------------------------------------------------------------------

    private static final Call REFINE = (api, params, recorded) -> {
        JsonNode sent = CliRow.exampleBody(recorded);
        assertThat(api.refine(slot(params), sent.path("transcript").asText(), sent.path("preamble").asText())
                .path("prompt").asText()).isNotBlank();
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
        rows.add(row("qits workspaces agents refine", SIGNED_IN_HARNESS, "refinePrompt", "refine", REFINE)
                .consumes("prompt"));
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
