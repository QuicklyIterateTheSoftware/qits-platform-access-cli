package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformUrlsTest {

    private static final String IDP = "https://idp.dev.wohlben.eu/idp";

    @Test
    void theHostComesFromTheIdpAddress() throws Exception {
        assertThat(PlatformUrls.projects(null, Map.of(), IDP)).isEqualTo("https://projects.dev.wohlben.eu");
        assertThat(PlatformUrls.events(null, Map.of(), IDP)).isEqualTo("https://events.dev.wohlben.eu");
        assertThat(PlatformUrls.observability(null, Map.of(), IDP)).isEqualTo("https://observability.dev.wohlben.eu");
        assertThat(PlatformUrls.ci(null, Map.of(), IDP)).isEqualTo("https://ci.dev.wohlben.eu");
        assertThat(PlatformUrls.ci(null, Map.of("QITS_CI_URL", "http://ci.prod.localhost:8080/"), IDP))
                .isEqualTo("http://ci.prod.localhost:8080");
        assertThat(PlatformUrls.observability("http://o.example/", Map.of("QITS_OBSERVABILITY_URL", "http://env.example"), IDP))
                .isEqualTo("http://o.example");
        assertThat(PlatformUrls.observability(null, Map.of("QITS_OBSERVABILITY_URL", "http://env.example"), IDP))
                .isEqualTo("http://env.example");
        assertThat(PlatformUrls.projects(null, Map.of(), "http://idp.prod.localhost:8080/idp/"))
                .isEqualTo("http://projects.prod.localhost:8080");
    }

    @Test
    void theFlagWinsThenTheVariable() throws Exception {
        Map<String, String> env = Map.of("QITS_PROJECTS_URL", "https://from-env.example/", "QITS_EVENTS_URL", " https://ev.example ");
        assertThat(PlatformUrls.projects("https://from-flag.example//", env, IDP)).isEqualTo("https://from-flag.example");
        assertThat(PlatformUrls.projects(" ", env, IDP)).isEqualTo("https://from-env.example");
        assertThat(PlatformUrls.events(null, env, IDP)).isEqualTo("https://ev.example");
    }

    @Test
    void theGitHostAndTheEnvironmentComeFromTheIdpToo() throws Exception {
        assertThat(PlatformUrls.gitHost(null, Map.of(), IDP)).isEqualTo("https://githost.dev.wohlben.eu");
        assertThat(PlatformUrls.gitHost(null, Map.of("QITS_GIT_HOST_URL", "http://githost.prod.localhost:8080"), IDP))
                .isEqualTo("http://githost.prod.localhost:8080");
        assertThat(PlatformUrls.environment(IDP)).isEqualTo("dev");
        assertThat(PlatformUrls.environment("http://idp.prod.localhost:8080/idp")).isEqualTo("prod");
        assertThatThrownBy(() -> PlatformUrls.environment("http://127.0.0.1:4000/idp")).hasMessageContaining("--audience");
        assertThatThrownBy(() -> PlatformUrls.environment("https://idp.localhost/idp")).hasMessageContaining("--audience");
    }

    /**
     * Inside, events is a platform-plane service on {@code qits-events} — and everything a person
     * can say about its address still comes first, because the day it moves again they must not
     * have to wait for a CLI release.
     */
    @Test
    void insideTheEventsDefaultIsThePlatformPlaneAliasAndEveryOverrideStillBeatsIt() throws Exception {
        Map<String, String> container = Map.of(
                Mode.CLIENT_ID, "dyn-workspace-352-m8m08",
                Mode.CLIENT_SECRET, "a secret",
                "QITS_WORKSPACE_DAEMON_URL", "ws://dev-qits-workspaces:8080/workspaces/daemon/352");

        assertThat(PlatformUrls.events(null, container, null)).isEqualTo("http://qits-events:8080");
        assertThat(PlatformUrls.projects(null, container, null)).as("still one per environment")
                .isEqualTo("http://dev-qits-projects:8080");

        Map<String, String> said = new HashMap<>(container);
        said.put("QITS_EVENTS_URL", "http://told-env:8080/");
        assertThat(PlatformUrls.events(null, said, null)).isEqualTo("http://told-env:8080");
        assertThat(PlatformUrls.events("http://told-flag:8080", said, null)).isEqualTo("http://told-flag:8080");

        Map<String, String> byUrlVariable = new HashMap<>(container);
        byUrlVariable.put("QITS_URL_EVENTS", "http://told-url-events:8080");
        assertThat(PlatformUrls.events(null, byUrlVariable, null)).isEqualTo("http://told-url-events:8080");
    }

    /** Outside, events is the idp host with its first label swapped, exactly as before. */
    @Test
    void theWorkstationVhostForEventsIsUnchanged() throws Exception {
        assertThat(PlatformUrls.events(null, Map.of(), IDP)).isEqualTo("https://events.dev.wohlben.eu");
        assertThat(PlatformUrls.events(null, Map.of(), "http://idp.prod.localhost:8080/idp/"))
                .isEqualTo("http://events.prod.localhost:8080");
    }

    @Test
    void anIdpNotCalledIdpNamesTheWayOut() {
        assertThatThrownBy(() -> PlatformUrls.projects(null, Map.of(), "http://127.0.0.1:4000/idp"))
                .isInstanceOf(CliFailure.class)
                .hasMessageContaining("--projects-url").hasMessageContaining("QITS_PROJECTS_URL")
                .satisfies(e -> assertThat(((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE));
        assertThatThrownBy(() -> PlatformUrls.events(null, Map.of(), "not a url"))
                .hasMessageContaining("--events-url").hasMessageContaining("QITS_EVENTS_URL");
    }
}
