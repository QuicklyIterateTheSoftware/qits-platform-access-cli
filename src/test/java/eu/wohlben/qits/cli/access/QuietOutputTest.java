package eu.wohlben.qits.cli.access;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nothing but the command's own answer may reach stdout, and one of the ways that has been broken
 * is not in this repository's code at all.
 * <p>
 * {@code quarkus.analytics.disabled} is fixed at build time. The binary's second home is a
 * workspace container whose environment sets {@code QUARKUS_ANALYTICS_DISABLED=true} for the Maven
 * builds the agent runs around it, and when a build time fixed property disagrees with its
 * environment Quarkus prints a {@code ConfigRecorder} WARN <em>on stdout</em>, in front of every
 * answer. A binary that is piped into {@code head} or {@code jq} then hands back corrupt data.
 * Writing the same value into the build settles it, and this holds that it stays written: a
 * deleted line is a quiet regression that only shows up inside a container.
 * <p>
 * The shipping form is proven where it is built — the release request recipe runs the static
 * binary's {@code --help} with that variable set and fails on the WARN. This is the cheap half of
 * the same check, read from the settings that were actually packaged.
 */
class QuietOutputTest {

    @Test
    void analyticsAreDisabledInTheBuiltConfiguration() throws Exception {
        Properties built = new Properties();
        try (InputStream packaged = getClass().getResourceAsStream("/application.properties")) {
            assertThat(packaged).as("application.properties is not on the test classpath").isNotNull();
            built.load(packaged);
        }

        assertThat(built.getProperty("quarkus.analytics.disabled"))
                .as("quarkus.analytics.disabled must be fixed at build time, or the workspace "
                        + "container's QUARKUS_ANALYTICS_DISABLED prints a WARN on stdout before every answer")
                .isEqualTo("true");
    }
}
