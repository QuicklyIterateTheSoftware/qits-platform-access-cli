package eu.wohlben.qits.cli.access.platform;

import org.junit.jupiter.api.Test;

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
