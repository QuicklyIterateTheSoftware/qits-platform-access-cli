package eu.wohlben.qits.cli.access.idp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdpUrlTest {

    @Test
    void theFlagWinsThenQitsIdpUrl() {
        Map<String, String> env = Map.of("QITS_IDP_URL", "https://idp.other.example/idp/",
                "QITS_DOMAIN", "wohlben.eu", "QITS_ENV_NAME", "dev");
        assertThat(IdpUrl.resolve("https://idp.flag.example/idp/", env)).isEqualTo("https://idp.flag.example/idp");
        assertThat(IdpUrl.resolve(null, env)).isEqualTo("https://idp.other.example/idp");
    }

    @Test
    void aDomainAndAnEnvironmentMakeTheIdpsOwnHost() {
        assertThat(IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "wohlben.eu", "QITS_ENV_NAME", "dev")))
                .isEqualTo("https://idp.dev.wohlben.eu/idp");
    }

    @Test
    void aDomainWithNoEnvironmentIsRefusedAndTheVariableNamed() {
        assertThatThrownBy(() -> IdpUrl.resolve(null, Map.of("QITS_DOMAIN", "wohlben.eu")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QITS_ENV_NAME");
    }

    @Test
    void noDomainMeansThePlatformOnThisMachine() {
        assertThat(IdpUrl.resolve(null, Map.of())).isEqualTo("http://idp.prod.localhost:8080/idp");
        assertThat(IdpUrl.resolve(null, Map.of("QITS_ENV_NAME", "dev"))).isEqualTo("http://idp.dev.localhost:8080/idp");
    }
}
