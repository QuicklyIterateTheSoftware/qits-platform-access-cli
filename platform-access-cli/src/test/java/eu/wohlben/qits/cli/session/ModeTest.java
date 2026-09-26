package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** Which home, and which addresses follow from it — from a fixed environment, in both directions. */
class ModeTest {

    private static final String IDP = "https://idp.dev.wohlben.eu/idp";

    private static final Map<String, String> CONTAINER = Map.of(
            Mode.CLIENT_ID, "dyn-workspace-352-m8m08",
            Mode.CLIENT_SECRET, "a secret",
            "QITS_WORKSPACE_DAEMON_URL", "ws://dev-qits-workspaces:8080/workspaces/daemon/352",
            "QITS_GIT_AUTH_TOKEN_URL", "http://qits-platform-idp:8080/idp/token");

    /** A project-agent container: no workspace daemon, and the two URLs that factory does inject. */
    private static final Map<String, String> PROJECT_AGENT = Map.of(
            Mode.CLIENT_ID, "dyn-project-agent-7-k22qd",
            Mode.CLIENT_SECRET, "a secret",
            "QITS_PROJECTS_DAEMON_URL", "http://qa-qits-projects:8080/projects/agents/daemon",
            "QITS_REPOSITORY_MCP_URL", "http://qa-qits-projects:8080/projects/mcp");

    private static PlatformEndpoints endpoints(Map<String, String> env, String idpUrl) {
        return new PlatformEndpoints(Mode.of(env), env, idpUrl);
    }

    @Test
    void theCommissionedPairIsTheSignal() {
        assertThat(Mode.of(Map.of())).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of(Mode.CLIENT_ID, "id"))).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of(Mode.CLIENT_SECRET, "secret"))).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(Map.of(Mode.CLIENT_ID, "id", Mode.CLIENT_SECRET, " "))).isEqualTo(Mode.WORKSTATION);
        assertThat(Mode.of(CONTAINER)).isEqualTo(Mode.IN_PLATFORM);
    }

    @Test
    void theOverrideIsHonouredInBothDirectionsButIsNotTheSignal() {
        assertThat(Mode.of(Map.of(Mode.OVERRIDE, "true"))).isEqualTo(Mode.IN_PLATFORM);
        Map<String, String> saysNo = Map.of(Mode.CLIENT_ID, "id", Mode.CLIENT_SECRET, "secret",
                Mode.OVERRIDE, "false");
        assertThat(Mode.of(saysNo)).isEqualTo(Mode.WORKSTATION);
    }

    @Test
    void aWorkstationDialsThePublicVhosts() throws CliFailure {
        PlatformEndpoints outside = endpoints(Map.of(), IDP);
        assertThat(outside.base("projects")).isEqualTo("https://projects.dev.wohlben.eu");
        assertThat(outside.base("ci")).isEqualTo("https://ci.dev.wohlben.eu");
        assertThat(outside.base("idp")).isEqualTo("https://idp.dev.wohlben.eu");
    }

    @Test
    void insideItDialsTheWireAliases() throws CliFailure {
        PlatformEndpoints inside = endpoints(CONTAINER, null);
        assertThat(inside.base("projects")).isEqualTo("http://dev-qits-projects:8080");
        assertThat(inside.base("ci")).isEqualTo("http://dev-qits-ci:8080");
        assertThat(inside.base("githost")).isEqualTo("http://dev-qits-githost:8080");
        // The idp reads the injected token url when the container carries one, and CONTAINER does.
        assertThat(inside.base("idp")).isEqualTo("http://qits-platform-idp:8080");
    }

    /**
     * <b>The bus carries the tier like everything else, and this assertion has been inverted
     * twice.</b>
     * <p>
     * It was first written because {@code qits events} hung forever on {@code dev-qits-events}: the
     * bus was on the platform plane and answered only on the bare {@code qits-events}, so the rule
     * produced a name with no DNS record. The fix was a table of bare aliases.
     * <p>
     * Deleting the plane inverted it again, and the table was what stayed behind — so the same
     * command reconnect-looped on {@code http://qits-events:8080} instead, in every agent container
     * on the estate. Measured on 2026-09-25: ConnectException, backing off 1s, 2s, 4s, 8s, 16s,
     * forever. The rule is the whole answer now.
     */
    @Test
    void theBusCarriesTheTierLikeEveryOtherApplication() throws CliFailure {
        assertThat(endpoints(CONTAINER, null).base("events"))
                .isEqualTo("http://dev-qits-events:8080");
        // A project-agent container reads its tier off the urls it carries, and this fixture's is
        // qa — so the bus follows it there. That is the whole of what changed.
        assertThat(endpoints(PROJECT_AGENT, null).base("events")).as("the tier reaches it")
                .isEqualTo("http://qa-qits-events:8080");
    }

    /** The idp keeps taking the address the container was told to mint at, when it was told one. */
    @Test
    void theIdpPrefersTheInjectedTokenUrlAndOtherwiseTakesTheRule() throws CliFailure {
        Map<String, String> told = new HashMap<>(CONTAINER);
        told.put("QITS_GIT_AUTH_TOKEN_URL", "http://qits-platform-idp.internal:9443/idp/token");
        assertThat(endpoints(told, null).base("idp")).isEqualTo("http://qits-platform-idp.internal:9443");

        // Untold, it is the rule and nothing else — no bare alias to fall back to.
        Map<String, String> untold = new HashMap<>(CONTAINER);
        untold.remove("QITS_GIT_AUTH_TOKEN_URL");
        assertThat(endpoints(untold, null).base("idp")).isEqualTo("http://dev-qits-idp:8080");
        assertThat(endpoints(untold, null).base("events")).as("only the idp reads that variable")
                .isEqualTo("http://dev-qits-events:8080");
    }

    /** Every service carries the tier, which is now the only rule there is. */
    @Test
    void everyServiceCarriesTheTier() throws CliFailure {
        assertThat(endpoints(CONTAINER, null).base("projects")).isEqualTo("http://dev-qits-projects:8080");
        assertThat(endpoints(CONTAINER, null).base("observability"))
                .isEqualTo("http://dev-qits-observability:8080");
    }

    @Test
    void theEnvironmentIsToldOrReadOffTheDaemonAddressOrDev() {
        assertThat(endpoints(CONTAINER, null).environment()).isEqualTo("dev");
        assertThat(endpoints(Map.of("QITS_ENV", "prod"), null).environment()).isEqualTo("prod");
        assertThat(endpoints(Map.of(), null).environment()).isEqualTo("dev");
        assertThat(endpoints(Map.of("QITS_WORKSPACE_DAEMON_URL", "ws://staging-qits-projects:8080/x"), null)
                .environment()).isEqualTo("staging");
    }

    @Test
    void aProjectAgentContainerReadsTheTierOffTheUrlsItDoesCarry() throws CliFailure {
        PlatformEndpoints agent = endpoints(PROJECT_AGENT, null);
        assertThat(agent.environment()).isEqualTo("qa");
        assertThat(agent.base("projects")).isEqualTo("http://qa-qits-projects:8080");
    }

    @Test
    void theRepositoryMcpUrlAnswersWhenItIsTheOnlyOne() {
        assertThat(endpoints(Map.of("QITS_REPOSITORY_MCP_URL", "http://qa-qits-projects:8080/projects/mcp"), null)
                .environment()).isEqualTo("qa");
    }

    @Test
    void toldStillWinsOverEveryUrl() {
        Map<String, String> agent = new HashMap<>(PROJECT_AGENT);
        agent.put("QITS_ENV", "prod");
        assertThat(endpoints(agent, null).environment()).isEqualTo("prod");
    }

    @Test
    void aValueThatIsNotAWireAliasIsPassedOver() {
        assertThat(endpoints(Map.of("QITS_PROJECTS_DAEMON_URL", "not a url at all"), null)
                .environment()).as("no throw, and no tier read off it").isEqualTo("dev");
        Map<String, String> mixed = Map.of(
                "QITS_PROJECTS_DAEMON_URL", "not a url at all",
                "QITS_REPOSITORY_MCP_URL", "http://qa-qits-projects:8080/projects/mcp");
        assertThat(endpoints(mixed, null).environment()).isEqualTo("qa");
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
