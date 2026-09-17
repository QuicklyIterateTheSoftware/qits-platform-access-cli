package eu.wohlben.qits.platformaccess.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The pin resolves, and it resolves to a CalVer.
 *
 * <p>The guard on the one thing about {@link PlatformAccessCliBinary} that can silently break: the
 * resource-filtering block in this module's pom. Without filtering the class reads the literal
 * {@code ${project.version}}, and qits-ci — which composes a download URL out of it — would ask the
 * artifacts store for a version no store can answer. That failure surfaces a repository away, in a
 * release step that cannot fetch its CLI, which is why the check lives here.
 *
 * <p>The <em>shape</em> and not a value: the version is whatever release stamped this tree, so an
 * expected literal would be a line every release has to edit and the first missed edit would fail a
 * correct build.
 */
class PlatformAccessCliBinaryTest {

  @Test
  void theVersionIsFilteredInAndIsACalVer() {
    String version = PlatformAccessCliBinary.VERSION;
    assertFalse(version.isBlank(), "the version is blank");
    assertFalse(
        version.startsWith("$"),
        () -> "unfiltered — is the pom's <resources> filtering block still there? got: " + version);
    assertTrue(
        version.matches("\\d+\\.\\d+\\.\\d+"),
        () -> "not a CalVer — is resource filtering still on? got: " + version);
  }

  @Test
  void theDaemonNameIsTheNameTheReleasePublishesUnder() {
    // A literal, because it is a cross-repository contract: `.config/qits/ci-event-release.yml`
    // declares `{type: daemon, name: qits-platform-access-cli}` and PUTs the binary to
    // `/artifacts/daemons/qits-platform-access-cli/<version>` — the same string, written by this
    // repository's own release recipe. A rename that moved only one side is what this catches.
    // Asserted against the literal rather than parsed out of the recipe: a test that read the YAML
    // would agree with whatever the recipe said, including a typo.
    assertEquals("qits-platform-access-cli", PlatformAccessCliBinary.DAEMON_NAME);
  }

  @Test
  void theCommandIsWhatTheBinaryIsCalledOnPath() {
    assertEquals("qits", PlatformAccessCliBinary.COMMAND);
  }
}
