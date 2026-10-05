package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The token home's addresses: the public vhosts of {@code QITS_DOMAIN}, and the domains refused. */
class PlatformEndpointsTest {

    private static final Map<String, String> RUNNER = Map.of(
            Mode.TOKEN, "the workspace token",
            "QITS_DOMAIN", "wohlben.eu");

    private static PlatformEndpoints endpoints(Map<String, String> env) {
        return new PlatformEndpoints(Mode.of(env), env, null);
    }

    @Test
    void theTokenHomeDialsThePublicVhostsOfItsDomain() throws CliFailure {
        PlatformEndpoints runner = endpoints(RUNNER);
        assertThat(runner.base("projects")).isEqualTo("https://projects.qits.wohlben.eu");
        assertThat(runner.base("idp")).isEqualTo("https://idp.qits.wohlben.eu");
        assertThat(runner.base("githost")).isEqualTo("https://githost.qits.wohlben.eu");
    }

    /** The pair and the wire aliases it would read are passed over: a runner resolves none of them. */
    @Test
    void theTokenHomeIgnoresWhatAContainerWouldDial() throws CliFailure {
        Map<String, String> both = new HashMap<>(RUNNER);
        both.put(Mode.CLIENT_ID, "id");
        both.put(Mode.CLIENT_SECRET, "secret");
        both.put("QITS_GIT_AUTH_TOKEN_URL", "http://dev-qits-idp:8080/idp/token");
        assertThat(endpoints(both).base("idp")).isEqualTo("https://idp.qits.wohlben.eu");
        assertThat(endpoints(both).base("ci")).isEqualTo("https://ci.qits.wohlben.eu");
    }

    @Test
    void anOverrideStillWins() throws CliFailure {
        Map<String, String> moved = new HashMap<>(RUNNER);
        moved.put("QITS_URL_PROJECTS", "https://elsewhere.example:9443/");
        assertThat(endpoints(moved).base("projects")).isEqualTo("https://elsewhere.example:9443");
        assertThat(endpoints(moved).base("ci")).isEqualTo("https://ci.qits.wohlben.eu");
    }

    @Test
    void aDomainThatCannotBePublicIsAUsageFailureNamingTheVariable() {
        for (String domain : new String[] {null, "", "   ", "localhost", "dev.localhost", "qits.LOCALHOST"}) {
            Map<String, String> env = new HashMap<>(RUNNER);
            if (domain == null) {
                env.remove("QITS_DOMAIN");
            } else {
                env.put("QITS_DOMAIN", domain);
            }
            assertThatThrownBy(() -> endpoints(env).base("projects")).as(String.valueOf(domain))
                    .isInstanceOfSatisfying(CliFailure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(CliFailure.USAGE))
                    .hasMessageContaining("QITS_DOMAIN")
                    .hasMessageNotContaining("the workspace token");
        }
    }

    /**
     * Untold, the idp inside is the rule: {@code <env>-qits-idp}. The application was renamed from
     * {@code qits-platform-idp} (its {@code deployments.yml} carries {@code renamed_from}), so the
     * rule is the one alias that answers and there is no special case for it.
     */
    @Test
    void insideTheUntoldIdpIsTheRule() throws CliFailure {
        Map<String, String> container = Map.of(Mode.CLIENT_ID, "id", Mode.CLIENT_SECRET, "secret", "QITS_ENV", "qa");
        assertThat(endpoints(container).base("idp")).isEqualTo("http://qa-qits-idp:8080");
    }
}
