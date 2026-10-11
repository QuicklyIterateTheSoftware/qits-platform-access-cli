package eu.wohlben.qits.cli.mcp;

import eu.wohlben.qits.cli.access.platform.CliContext;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkiverse.mcp.server.test.McpAssured.McpStreamableTestClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service as a client meets it: no bearer is a 401 before any tool runs, a bearer gets the tools
 * and the instructions, and a call reaches the platform with that same bearer.
 */
@QuarkusTest
@TestProfile(BearerProfile.class)
class McpServiceTest {

    @Inject
    ProjectsStub projects;

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    private static McpStreamableTestClient client(String token) {
        return McpAssured.newStreamableClient()
                .setStateless()
                .setMcpPath("/mcp")
                .setBearerToken(token)
                .build()
                .connect();
    }

    @Test
    void withoutABearerNothingIsServed() {
        given().contentType("application/json").accept("application/json, text/event-stream").body(INITIALIZE)
                .when().post("/mcp").then().statusCode(401);
    }

    @Test
    void aTokenForAnotherAudienceIsRefused() {
        given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + BearerProfile.agentToken("somebody-else")).body(INITIALIZE)
                .when().post("/mcp").then().statusCode(401);
    }

    @Test
    void withABearerTheToolsAreListedAndTheExclusionsExplained() {
        McpStreamableTestClient client = client(BearerProfile.agentToken("qits-platform"));
        assertThat(System.getProperty(CliContext.MCP_SERVICE)).as("the guard is armed").isNotNull();
        client.when().toolsList(page -> {
            assertThat(page.nextCursor()).as("one page").isNull();
            assertThat(page.tools()).extracting(McpAssured.ToolInfo::name)
                    .contains("projects_list", "work_update", "events_query", "observe_query")
                    .doesNotContain("login", "mcp-credential", "events");
            JsonObject update = page.findByName("work_update").inputSchema();
            assertThat(update.getJsonObject("properties").fieldNames()).contains("entity", "output", "payload")
                    .doesNotContain("projects-url");
            assertThat(update.getJsonObject("properties").getJsonObject("payload").getString("type")).isEqualTo("object");
        }).thenAssertResults();
    }

    /** The initialize answer says what is not served, and why, from the same walk as the tools. */
    @Test
    void theInstructionsListTheExclusions() {
        String body = given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + BearerProfile.agentToken("qits-platform")).body(INITIALIZE)
                .when().post("/mcp").then().statusCode(200).extract().asString();
        assertThat(body).contains("Not served here (").contains("qits login: needs a browser and a person")
                .contains("qits events: runs until stopped").contains("qits mcp-credential: only means something");
    }

    @Test
    void aCallRunsTheCommandWithTheCallersBearer() {
        String token = BearerProfile.agentToken("qits-platform");
        projects.seen().clear();
        client(token).when().toolsCall("projects_list", Map.of(), response -> {
            String text = response.content().stream().map(c -> c.asText().text()).collect(Collectors.joining("\n"));
            assertThat(response.isError()).as(text).isFalse();
            assertThat(new JsonObject(text).getJsonArray("entries")).hasSize(1);
        }).thenAssertResults();
        assertThat(projects.seen()).containsExactly("Bearer " + token);
    }

    // --- the read-only mode ---

    private static McpStreamableTestClient readOnlyClient(String token) {
        return McpAssured.newStreamableClient()
                .setStateless()
                .setMcpPath("/mcp?agentReadOnly=true")
                .setBearerToken(token)
                .build()
                .connect();
    }

    @Test
    void aReadOnlyConnectionListsOnlyTheReads() {
        readOnlyClient(BearerProfile.agentToken("qits-platform")).when().toolsList(page ->
                assertThat(page.tools()).extracting(McpAssured.ToolInfo::name)
                        .contains("projects_list", "work_details", "work_dossier_get", "observe_errors")
                        .doesNotContain("work_update", "work_delete", "work_dossier_put", "ci_retry",
                                "release-request_create"))
                .thenAssertResults();
    }

    @Test
    void aReadOnlyConnectionRunsAReadAsUsual() {
        projects.seen().clear();
        readOnlyClient(BearerProfile.agentToken("qits-platform")).when().toolsCall("projects_list", Map.of(),
                response -> assertThat(response.isError()).isFalse()).thenAssertResults();
        assertThat(projects.seen()).hasSize(1);
    }

    /** A raw JSON-RPC request in a session opened at {@code path}, as any client could send it. */
    private static String post(String path, String body) {
        String token = BearerProfile.agentToken("qits-platform");
        String session = given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).body(INITIALIZE)
                .when().post(path).then().statusCode(200).extract().header("Mcp-Session-Id");
        return given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).header("Mcp-Session-Id", session).body(body)
                .when().post(path).then().extract().asString();
    }

    /** Calling a write it was never shown is refused before anything runs, whatever the client does. */
    @Test
    void aReadOnlyConnectionRefusesAWriteCalledAnyway() {
        projects.seen().clear();
        projects.deleted().clear();
        String call = """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"projects_list","arguments":{}}}""";
        String write = call.replace("projects_list", "work_delete").replace("{}", "{\"entity\":\"qits-1\"}");
        for (String marker : new String[] {"true", "TRUE", "yes", ""}) {
            String body = post("/mcp?agentReadOnly=" + marker, write);
            assertThat(body).as(marker).contains("\"error\"").contains("work_delete").doesNotContain("\"result\"");
        }
        assertThat(projects.deleted()).as("no write reached the platform").isEmpty();
        assertThat(post("/mcp?agentReadOnly=false", call)).contains("\"result\"");
        assertThat(post("/mcp", write)).as("without the marker a write runs").contains("\"result\"");
        assertThat(projects.deleted()).as("the write without the marker ran").containsExactly("qits-1");
    }
}
