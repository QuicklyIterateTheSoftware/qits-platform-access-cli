package eu.wohlben.qits.cli.access.idp;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdpUrlTest {

    @Test
    void theFlagWinsThenQitsIdpUrl() throws CliFailure {
        Map<String, String> env = Map.of("QITS_IDP_URL", "https://idp.other.example/idp/", "QITS_DOMAIN", "example.org");
        assertThat(IdpUrl.resolve("https://idp.flag.example/idp/", env)).isEqualTo("https://idp.flag.example/idp");
        assertThat(IdpUrl.resolve(null, env)).isEqualTo("https://idp.other.example/idp");
    }

    @Test
    void theFlagAndQitsIdpUrlNeedNoDomain() throws CliFailure {
        assertThat(IdpUrl.resolve("https://idp.flag.example/idp/", Map.of())).isEqualTo("https://idp.flag.example/idp");
        assertThat(IdpUrl.resolve(null, Map.of("QITS_IDP_URL", "https://idp.other.example/idp")))
                .isEqualTo("https://idp.other.example/idp");
    }

    @Test
    void aDomainMakesThePlatformProjectsIdpHost() throws CliFailure {
        assertThat(IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "example.org")))
                .isEqualTo("https://idp.qits.example.org/idp");
    }

    @Test
    void anEnvironmentNameChangesNothing() throws CliFailure {
        assertThat(IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "example.org", "QITS_ENV_NAME", "dev")))
                .isEqualTo("https://idp.qits.example.org/idp");
        assertThatThrownBy(() -> IdpUrl.resolve(null, Map.of("QITS_ENV_NAME", "dev")))
                .isInstanceOf(CliFailure.class);
    }

    @Test
    void nothingSetIsAUsageFailureNamingWhatWouldSayIt() {
        for (Map<String, String> env : java.util.List.of(Map.<String, String>of(),
                Map.of("QITS_IDP_URL", " ", "QITS_DOMAIN", " "))) {
            assertThatThrownBy(() -> IdpUrl.resolve(" ", env))
                    .isInstanceOfSatisfying(CliFailure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(CliFailure.USAGE))
                    .hasMessageContaining("--idp-url")
                    .hasMessageContaining("QITS_IDP_URL")
                    .hasMessageContaining("QITS_DOMAIN")
                    .hasMessageNotContaining("wohlben");
        }
    }

    @Test
    void aDomainThatCannotBePublicIsRefused() {
        for (String domain : java.util.List.of("localhost", "qits.localhost")) {
            assertThatThrownBy(() -> IdpUrl.resolve(null, Map.of("QITS_DOMAIN", domain)))
                    .isInstanceOfSatisfying(CliFailure.class,
                            failure -> assertThat(failure.exitCode()).isEqualTo(CliFailure.USAGE))
                    .hasMessageContaining("QITS_DOMAIN");
        }
    }
}
