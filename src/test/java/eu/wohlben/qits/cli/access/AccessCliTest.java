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
    void theHelpNamesEveryCommand(LaunchResult result) {
        assertThat(result.getOutput()).contains("qits").contains("login").contains("session-daemon")
                .contains("projects").contains("repositories").contains("release-request").contains("events")
                .contains("observe").contains("git-login").contains("git-credential")
                .contains("Platform rules:").contains("Exit codes:");
        assertThat(result.getOutputStream()).as("help is for agents and stays hidden")
                .noneMatch(line -> line.strip().startsWith("help "));
    }

    @Test
    @Launch({"help", "skill"})
    void helpSkillPrintsTheSkill(LaunchResult result) {
        assertThat(result.getOutput()).startsWith("---\nname: qits\n").contains("## qits observe")
                .contains("## qits release-request join");
    }

    @Test
    @Launch({"observe", "--help"})
    void observeHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--filter").contains("--observability-url").contains("--output")
                .contains("level>=V").contains("attr.<key>");
    }

    @Test
    @Launch(value = {"observe"}, exitCode = 2)
    void observeWithoutAFilterIsAUsageError(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("--filter");
    }

    @Test
    @Launch({"git-login", "--help"})
    void gitLoginHasItsOptions(LaunchResult result) {
        assertThat(result.getOutput()).contains("--idp-url").contains("--git-host").contains("--audience")
                .contains("--timeout").contains("--no-browser").contains("--configure");
    }

    @Test
    @Launch({"release-request", "create", "--help"})
    void createHasItsOptionsAndTheInheritedOnes(LaunchResult result) {
        assertThat(result.getOutput()).contains("--branch").contains("--summary").contains("--priority")
                .contains("--project").contains("--repository").contains("--output");
    }

    @Test
    @Launch(value = {"projects"}, exitCode = 2)
    void aGroupWithoutASubcommandIsAUsageError(LaunchResult result) {
        assertThat(result.getErrorOutput()).contains("Name a command");
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
