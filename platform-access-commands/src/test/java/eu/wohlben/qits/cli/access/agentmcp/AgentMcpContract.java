package eu.wohlben.qits.cli.access.agentmcp;

import eu.wohlben.qits.cli.access.contracts.CliPact;
import eu.wohlben.qits.cli.access.contracts.CliRow;
import eu.wohlben.qits.cli.access.contracts.Reached;
import eu.wohlben.qits.cli.access.platform.PlatformClient;
import eu.wohlben.qits.pact.consumer.GoldenMasters.Operation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>What {@code qits agent-mcp credentials} asks qits-projects, and why</b> (epic qits-765,
 * qits-1394). The rows belong to qits-projects' contract ({@code projects/ProjectsContract}); they
 * are written here because {@link AgentMcpApi} is this package's. Pending until a pinned
 * {@code qits-projects-golden-masters} records the state.
 */
public final class AgentMcpContract {

    /** The client the rows call through, for the contract that holds them. */
    public static final Class<?> CLIENT = AgentMcpApi.class;

    /** The catalog with at least one stored credential; {@code key} ({@code env.<VAR>}) is one of them. */
    static final String WITH_CREDENTIALS = "the agent MCP catalog with a stored credential";

    private static final CliPact MASTERS = CliPact.of("qits-projects-service", "qits-projects");

    @FunctionalInterface
    interface Call {
        void run(AgentMcpApi api, Map<String, String> params, Operation recorded) throws Exception;
    }

    private static CliRow.Call api(Call call) {
        return (url, recorded) -> call.run(new AgentMcpApi(new PlatformClient(Reached.CREDENTIAL), url),
                recorded.params(), recorded);
    }

    private static final Call LIST = (api, params, recorded) ->
            assertThat(api.credentials().path("credentials")).isNotEmpty()
                    .allSatisfy(c -> assertThat(c.path("key").asText()).isNotBlank());

    private static final Call SET = (api, params, recorded) ->
            assertThat(api.putCredential(params.get("key"), CliRow.exampleBody(recorded).path("value").asText())
                    .path("key").asText()).isEqualTo(params.get("key"));

    private static final Call DELETE = (api, params, recorded) -> api.deleteCredential(params.get("key"));

    /** The rows, for qits-projects' contract. */
    public static final List<CliRow> ROWS = List.of(
            row("qits agent-mcp credentials list", WITH_CREDENTIALS, "listAgentMcpCredentials", "credentials", LIST)
                    .consumes(MASTERS.held(WITH_CREDENTIALS, "listAgentMcpCredentials",
                            List.of("credentials[].key", "credentials[].updatedAt", "credentials[].updatedBy")))
                    .request("GET /projects/api/agent-mcp-catalog/credentials"),
            row("qits agent-mcp credentials set", WITH_CREDENTIALS, "putAgentMcpCredential", "putCredential", SET)
                    .consumes(MASTERS.held(WITH_CREDENTIALS, "putAgentMcpCredential", List.of("key")))
                    .request("PUT /projects/api/agent-mcp-catalog/credentials/{key}"),
            row("qits agent-mcp credentials delete", WITH_CREDENTIALS, "deleteAgentMcpCredential", "deleteCredential",
                    DELETE)
                    .request("DELETE /projects/api/agent-mcp-catalog/credentials/{key}"));

    private static CliRow row(String command, String state, String operationId, String method, Call call) {
        return CliRow.of(command, state, operationId, "AgentMcpApi#" + method, api(call));
    }

    private AgentMcpContract() {
    }
}
