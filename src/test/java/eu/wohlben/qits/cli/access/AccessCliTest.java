package eu.wohlben.qits.cli.access;

import io.quarkus.test.junit.main.Launch;
import io.quarkus.test.junit.main.LaunchResult;
import io.quarkus.test.junit.main.QuarkusMainTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command tree, through Quarkus. This is also the test that augments the application, so a
 * build-time break fails `clean verify` although the JVM build skips Quarkus' build goal.
 */
@QuarkusMainTest
class AccessCliTest {

    @Test
    @Launch("--help")
    void theHelpNamesBothCommands(LaunchResult result) {
        assertThat(result.getOutput()).contains("qits").contains("login").contains("session-daemon");
    }

    @Test
    @Launch(value = {}, exitCode = 2)
    void noCommandIsAUsageError(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("Name a command");
    }

    @Test
    @Launch(value = {"session-daemon", "--margin", "900"}, exitCode = 2)
    void aMarginNearTheTokenLifetimeIsRefused(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("--margin");
    }

    @Test
    @Launch({"login", "--help"})
    void loginHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--idp-url").contains("--no-browser");
    }
}
