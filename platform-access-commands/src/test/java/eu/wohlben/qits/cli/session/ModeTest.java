package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Which home, and which addresses follow from it — from a fixed environment, in both directions. */
class ModeTest {

    private static final String IDP = "https://idp.qits.wohlben.eu/idp";

    /**
     * The MCP service's in-platform environment, with the wire-alias variables an older container
     * carried beside it: none of them is read for an address any more.
     */
    private static final Map<String, String> CONTAINER = Map.of(
            Mode.OVERRIDE, "true",
            "QITS_DOMAIN", "wohlben.eu",
            "QITS_WORKSPACE_DAEMON_URL", "ws://dev-qits-workspaces:8080/workspaces/daemon/352",
            "QITS_PROJECTS_DAEMON_URL", "http://qa-qits-projects:8080/projects/agents/daemon",
            "QITS_PLATFORM_MCP_URL", "http://qa-qits-platform-access-mcp:8080/mcp",
            "QITS_GIT_AUTH_TOKEN_URL", "http://qits-platform-idp:8080/idp/token");

    private static PlatformEndpoints endpoints(Map<String, String> env, String idpUrl) {
        return new PlatformEndpoints(Mode.of(env), env, idpUrl);
    }

    /**
     * Only the override leads inside. The retired commissioned client pair is no signal any more:
     * a container still carrying one is a workstation unless something else says otherwise.
     */
    @Test
    void onlyTheOverrideLeadsInside() {
        assertThat(Mode.of(Map.of())).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of("QITS_COMMISSIONED_CLIENT_ID", "id", "QITS_COMMISSIONED_CLIENT_SECRET", "secret")))
                .isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of("QITS_WORKSPACE_DAEMON_URL", "ws://dev-qits-workspaces:8080/workspaces/daemon/352")))
                .as("a wire alias in the environment is an address, not a home").isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(CONTAINER)).isEqualTo(Mode.IN_PLATFORM);
    }

    @Test
    void theOverrideIsHonouredInBothDirections() {
        assertThat(Mode.of(Map.of(Mode.OVERRIDE, "true"))).isEqualTo(Mode.IN_PLATFORM);
        assertThat(Mode.of(Map.of(Mode.OVERRIDE, " TRUE "))).isEqualTo(Mode.IN_PLATFORM);
        assertThat(Mode.of(Map.of(Mode.OVERRIDE, "false"))).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of(Mode.OVERRIDE, " "))).as("a blank override is no override").isEqualTo(Mode.WORKSTATION);
    }

    /** The token, then the override, then the session file; and the override never beats a token. */
    @Test
    void aTokenWinsOverTheOverrideWhichWinsOverTheSession() {
        Map<String, String> both = new HashMap<>(CONTAINER);
        both.put(Mode.TOKEN, "the workspace token");
        assertThat(Mode.of(both)).isEqualTo(Mode.EDGE_TOKEN);
        assertThat(Mode.of(Map.of(Mode.TOKEN, "t"))).isEqualTo(Mode.EDGE_TOKEN);
        assertThat(Mode.of(CONTAINER)).isEqualTo(Mode.IN_PLATFORM);
        assertThat(Mode.of(Map.of())).isEqualTo(Mode.WORKSTATION);

        both.put(Mode.TOKEN, "  ");
        assertThat(Mode.of(both)).as("a blank token is no token").isEqualTo(Mode.IN_PLATFORM);
    }

    @Test
    void theOverrideDoesNotBeatAToken() {
        assertThat(Mode.of(Map.of(Mode.TOKEN, "t", Mode.OVERRIDE, "false"))).isEqualTo(Mode.EDGE_TOKEN);
        assertThat(Mode.of(Map.of(Mode.TOKEN, "t", Mode.OVERRIDE, "true"))).isEqualTo(Mode.EDGE_TOKEN);
    }

    @Test
    void eachHomeAnswersOnlyItsOwnPredicate() {
        assertThat(Mode.EDGE_TOKEN.edgeToken()).isTrue();
        assertThat(Mode.EDGE_TOKEN.inPlatform()).isFalse();
        assertThat(Mode.IN_PLATFORM.inPlatform()).isTrue();
        assertThat(Mode.IN_PLATFORM.edgeToken()).isFalse();
        assertThat(Mode.WORKSTATION.inPlatform()).isFalse();
        assertThat(Mode.WORKSTATION.edgeToken()).isFalse();
    }

    @Test
    void aWorkstationDialsThePublicVhosts() throws CliFailure {
        PlatformEndpoints outside = endpoints(Map.of(), IDP);
        assertThat(outside.base("projects")).isEqualTo("https://projects.qits.wohlben.eu");
        assertThat(outside.base("ci")).isEqualTo("https://ci.qits.wohlben.eu");
        assertThat(outside.base("idp")).isEqualTo("https://idp.qits.wohlben.eu");
    }

    /**
     * <b>Inside, every address is the public vhost of {@code QITS_DOMAIN}</b> (qits-1436): the CLI
     * never dials a wire alias, in-platform included. The idp is no exception any more — the
     * injected token url is not read for an address — and neither is the bus.
     */
    @Test
    void insideItDialsThePublicVhostsOfItsDomain() throws CliFailure {
        PlatformEndpoints inside = endpoints(CONTAINER, null);
        assertThat(inside.base("projects")).isEqualTo("https://projects.qits.wohlben.eu");
        assertThat(inside.base("ci")).isEqualTo("https://ci.qits.wohlben.eu");
        assertThat(inside.base("githost")).isEqualTo("https://githost.qits.wohlben.eu");
        assertThat(inside.base("idp")).isEqualTo("https://idp.qits.wohlben.eu");
        assertThat(inside.base("events")).isEqualTo("https://events.qits.wohlben.eu");
        assertThat(inside.base("observability")).isEqualTo("https://observability.qits.wohlben.eu");
    }

    /** Without a public domain there is nothing inside to dial, and the failure names the variable. */
    @Test
    void insideWithoutADomainIsAUsageFailure() {
        Map<String, String> untold = new HashMap<>(CONTAINER);
        untold.remove("QITS_DOMAIN");
        assertThatThrownBy(() -> endpoints(untold, null).base("projects"))
                .isInstanceOfSatisfying(CliFailure.class,
                        failure -> assertThat(failure.exitCode()).isEqualTo(CliFailure.USAGE))
                .hasMessageContaining("QITS_DOMAIN");
    }

    /** The label is a display: told, or {@code dev}; never read off a wire alias. */
    @Test
    void theEnvironmentIsToldOrDev() {
        assertThat(endpoints(CONTAINER, null).environment()).isEqualTo("dev");
        assertThat(endpoints(Map.of("QITS_ENV", "prod"), null).environment()).isEqualTo("prod");
        assertThat(endpoints(Map.of(), null).environment()).isEqualTo("dev");
        assertThat(endpoints(Map.of("QITS_WORKSPACE_DAEMON_URL", "ws://staging-qits-projects:8080/x"), null)
                .environment()).as("a wire alias is no longer read for the tier").isEqualTo("dev");
        Map<String, String> told = new HashMap<>(CONTAINER);
        told.put("QITS_ENV", "qa");
        assertThat(endpoints(told, null).environment()).isEqualTo("qa");
    }

    @Test
    void everyBaseCanBeOverriddenInEitherHome() throws CliFailure {
        assertThat(endpoints(Map.of("QITS_URL_PROJECTS", "http://elsewhere:9000/"), IDP).base("projects"))
                .isEqualTo("http://elsewhere:9000");
        Map<String, String> inside = new HashMap<>(CONTAINER);
        inside.put("QITS_URL_CI", "http://moved:8080");
        assertThat(endpoints(inside, null).base("ci")).isEqualTo("http://moved:8080");
    }

    @Test
    void theBeanDecidesOnceAndAnswersTheSame() {
        PlatformMode held = new PlatformMode(CONTAINER);
        assertThat(held.mode()).isEqualTo(Mode.IN_PLATFORM);
        assertThat(held.inPlatform()).isTrue();
        assertThat(new PlatformMode(Map.of()).inPlatform()).isFalse();
    }
}
