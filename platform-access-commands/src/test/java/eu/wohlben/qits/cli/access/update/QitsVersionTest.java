package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.AccessCli;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/** The built-in version, what {@code -V} prints, and how two versions are ordered. */
class QitsVersionTest {

    @Test
    void theVersionIsTheReactorsFilteredIn() {
        String built = System.getProperty("qits.project.version");
        assertThat(built).as("surefire hands the reactor's version in").isNotBlank();

        assertThat(QitsVersion.current()).contains(built);
    }

    @Test
    void dashVPrintsTheVersionAloneOnTheTopCommandAndOnASubcommand() {
        String version = QitsVersion.current().orElseThrow();

        assertThat(run("-V")).isEqualTo(version + System.lineSeparator());
        assertThat(run("--version")).isEqualTo(version + System.lineSeparator());
        assertThat(run("update", "-V")).as("provided to every subcommand").isEqualTo(version + System.lineSeparator());
        assertThat(run("work", "list", "-V")).isEqualTo(version + System.lineSeparator());
    }

    private static String run(String... args) {
        StringWriter out = new StringWriter();
        CommandLine cli = QitsVersion.provideTo(new CommandLine(new AccessCli()));
        cli.setOut(new PrintWriter(out, true));
        assertThat(cli.execute(args)).isZero();
        return out.toString();
    }

    @Test
    void releaseVersionsAreDigitsAndDots() {
        assertThat(QitsVersion.isRelease("2026.919.40352")).isTrue();
        assertThat(QitsVersion.isRelease("2026.1015.175513")).isTrue();
        assertThat(QitsVersion.isRelease("1.0-SNAPSHOT")).isFalse();
        assertThat(QitsVersion.isRelease("${project.version}")).isFalse();
        assertThat(QitsVersion.isRelease("2026.919.204627-main.geec9f2c")).isFalse();
        assertThat(QitsVersion.isRelease("")).isFalse();
        assertThat(QitsVersion.isRelease(null)).isFalse();
        assertThat(QitsVersion.isRelease("../../etc")).isFalse();
    }

    @Test
    void newerComparesEachSegmentAsANumber() {
        // No zero padding: 1015 is after 919, though "1015" < "919" as text.
        assertThat(QitsVersion.newer("2026.1015.1", "2026.919.40352")).isTrue();
        assertThat(QitsVersion.newer("2026.919.40353", "2026.919.40352")).isTrue();
        assertThat(QitsVersion.newer("2027.101.1", "2026.1231.235959")).isTrue();
        assertThat(QitsVersion.newer("2026.919.40352", "2026.919.40352")).as("the same is not newer").isFalse();
        assertThat(QitsVersion.newer("2026.919.4035", "2026.919.40352")).isFalse();
        assertThat(QitsVersion.newer("2026.918.235959", "2026.919.0")).isFalse();
    }

    @Test
    void nothingThatIsNotAReleaseVersionDecidesAnUpdate() {
        assertThat(QitsVersion.newer("2026.1015.1", "1.0-SNAPSHOT")).isFalse();
        assertThat(QitsVersion.newer("2027.1.1-rc", "2026.919.40352")).isFalse();
        assertThat(QitsVersion.newer(null, "2026.919.40352")).isFalse();
    }
}
