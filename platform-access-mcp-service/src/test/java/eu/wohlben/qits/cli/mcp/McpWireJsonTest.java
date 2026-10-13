package eu.wohlben.qits.cli.mcp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a {@code tools/call} answer looks like on the wire, as bytes rather than as a client library
 * parses them: no member is ever sent as {@code null}. MCP clients validate a result against a schema
 * where {@code _meta}, {@code annotations} and {@code structuredContent} may be absent but never
 * null, and reject the whole result when one is (Claude Code: {@code invalid_union} at
 * {@code ["content",0]}), so a call that succeeded reads as a call that failed.
 * <p>
 * <b>On a JVM this cannot fail the way the shipped service did</b> (qits-1226): Arc is up long before
 * the Vert.x JSON codec is first used, so the codec always copies the mapper with the extension's
 * NON_NULL mixins. The failure lived in the native image, where the codec can be initialised at build
 * time with a bare mapper; {@link McpWireJsonIT} runs this same test against the native runner, and
 * the release request runs that one in docker/Dockerfile's {@code service-it} stage. This one guards
 * the library's own path — a quarkus-mcp-server upgrade that writes nulls again — in a plain
 * {@code mvn verify}.
 */
@QuarkusTest
@TestProfile(BearerProfile.class)
class McpWireJsonTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    private static final String CALL = """
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"projects_list","arguments":{}}}""";

    /**
     * Any result will do, an error result included: success or not, every content item is a
     * TextContent in a ToolResponse, written by the same codec. That matters for the native run,
     * where {@link ProjectsStub} is not in the runner and the command's GET answers 404.
     */
    @Test
    void aToolResultCarriesNoNullMembers() {
        String token = BearerProfile.agentToken("qits-platform");
        String session = given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).body(INITIALIZE)
                .when().post("/mcp").then().statusCode(200).extract().header("Mcp-Session-Id");
        String body = given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).header("Mcp-Session-Id", session).body(CALL)
                .when().post("/mcp").then().statusCode(200).extract().asString();
        assertThat(body).contains("\"result\"").contains("\"content\"")
                .doesNotContain("\"_meta\":null")
                .doesNotContain("\"annotations\":null")
                .doesNotContain("\"structuredContent\":null");
    }
}
