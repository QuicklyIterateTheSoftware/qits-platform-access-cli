package eu.wohlben.qits.cli.mcp;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Only the issuer qits-idp publishes is accepted</b> (qits-708). The shipped tenant has discovery
 * on and no issuer key at all, so the issuer quarkus-oidc checks every bearer's {@code iss} against
 * is the {@code issuer} of the discovery document it reads from {@code auth-server-url}. Before
 * qits-708 discovery was off and no issuer was configured, and quarkus-oidc then checks no {@code
 * iss} at all: a token signed by a published key and addressed to {@code qits-platform} was accepted
 * whoever claimed to have issued it.
 * <p>
 * The far side is {@link IssuerIdp}: a discovery document whose {@code issuer} is {@link
 * IssuerIdp#ISSUER} and a JWKS holding the suite's key. The profile moves only {@code
 * auth-server-url} (and the services the commands call), unlike {@link BearerProfile}, whose {@code
 * public-key} makes quarkus-oidc build a local-key tenant with no issuer to check: the tenant under
 * test is the shipped one, with discovery, the published issuer, the shipped audience and keys by
 * {@code kid}.
 */
@QuarkusTest
@TestProfile(BearerIssuerTest.DiscoveringTheIdp.class)
class BearerIssuerTest {

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
             "capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""";

    /** Another environment's idp address, the issuer tokens carried before qits-730: trusted under no name. */
    private static final String FOREIGN_ISSUER = "http://dev-qits-idp:8080/idp";

    /** The tenant pointed at {@link IssuerIdp}, and nothing else of the shipped configuration moved. */
    public static class DiscoveringTheIdp implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.auth-server-url", IssuerIdp.ensureStarted() + "/idp",
                    "QITS_PROJECTS_URL", "http://localhost:${quarkus.http.test-port:8081}",
                    "QITS_WORKSPACES_URL", "http://localhost:${quarkus.http.test-port:8081}");
        }
    }

    @Test
    void theTenantReadsTheDiscoveryDocument() {
        // The keys are fetched when a bearer first needs one (resolve-early=false), so send one.
        given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + BearerProfile.agentToken("qits-platform", null, IssuerIdp.ISSUER))
                .body(INITIALIZE).when().post("/mcp");
        assertThat(IssuerIdp.recorded()).as("the tenant read the idp's discovery document, then its keys")
                .contains("/idp/.well-known/openid-configuration", "/idp/jwks");
    }

    @Test
    void aBearerFromThePublishedIssuerIsAccepted() {
        given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + BearerProfile.agentToken("qits-platform", null, IssuerIdp.ISSUER))
                .body(INITIALIZE).when().post("/mcp").then().statusCode(200);
    }

    @Test
    void aBearerFromAnotherIssuerIsRefused() {
        // Signed with the published key, the right audience, not expired: only `iss` is wrong.
        given().contentType("application/json").accept("application/json, text/event-stream")
                .header("Authorization", "Bearer " + BearerProfile.agentToken("qits-platform", null, FOREIGN_ISSUER))
                .body(INITIALIZE).when().post("/mcp").then().statusCode(401);
    }
}
