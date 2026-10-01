package eu.wohlben.qits.cli.access.idp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IdpUrlTest {

    @Test
    void theFlagWinsThenQitsIdpUrl() {
        Map<String, String> env = Map.of("QITS_IDP_URL", "https://idp.other.example/idp/", "QITS_DOMAIN", "wohlben.eu");
        assertThat(IdpUrl.resolve("https://idp.flag.example/idp/", env)).isEqualTo("https://idp.flag.example/idp");
        assertThat(IdpUrl.resolve(null, env)).isEqualTo("https://idp.other.example/idp");
    }

    @Test
    void aDomainMakesThePlatformProjectsIdpHost() {
        assertThat(IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "example.org")))
                .isEqualTo("https://idp.qits.example.org/idp");
    }

    @Test
    void anEnvironmentNameChangesNothing() {
        assertThat(IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "example.org", "QITS_ENV_NAME", "dev")))
                .isEqualTo("https://idp.qits.example.org/idp");
        assertThat(IdpUrl.resolve(null, Map.of("QITS_ENV_NAME", "dev"))).isEqualTo("https://idp.qits.wohlben.eu/idp");
    }

    @Test
    void nothingSetMeansThePlatformInstallShUses() {
        assertThat(IdpUrl.resolve(null, Map.of())).isEqualTo("https://idp.qits.wohlben.eu/idp");
    }
}
