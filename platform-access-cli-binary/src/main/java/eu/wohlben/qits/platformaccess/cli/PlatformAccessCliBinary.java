package eu.wohlben.qits.platformaccess.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * <b>What this jar was released with</b>: the coordinate — store name, command name and version —
 * of the {@code qits} binary the release that published this artifact also published.
 *
 * <p><b>This jar carries no bytes of the binary.</b> It carries three strings. The binary itself is
 * ~50 MB of static native image, published as bytes to the artifacts store's {@code daemons}
 * surface under {@link #DAEMON_NAME} and {@link #VERSION}; this jar only names it.
 *
 * <p>The whole thing exists to be depended on, by qits-ci. Its composed release steps hand a step
 * the {@code qits} CLI, and until now they downloaded <em>whatever was latest</em> in the daemons
 * store at the moment the step started. That is a shared, unversioned, unreviewed input to every
 * release on the platform at once: on 2026-09-13 one bad CLI release broke every composed release
 * simultaneously, and nothing in any consumer's tree had changed. There was no line anybody could
 * revert, because there was no line.
 *
 * <p>Depending on a coordinate fixes that. qits-ci's <em>pom</em> decides which CLI its release
 * steps run; the dependency has to <em>resolve</em> for qits-ci to build, so a version that does
 * not exist is a red build rather than a broken release much later; the maintenance train moves the
 * pom line like any internal library; and qits-ci's own release request is what gates the move. A
 * bad CLI then breaks one repository's gate instead of every release at once, and the fix is a
 * revert of one line.
 *
 * <p><b>The value is {@code ${project.version}}, resolved at build time</b> into {@code
 * platform-access-cli-binary.properties} beside this class rather than written down. The release
 * stamps the reactor's poms with the version it is about to tag, and the same release publishes the
 * binary under that version — so "the jar's version", "the binary's version" and "the
 * daemons-store coordinate" are one string by construction rather than three things to keep in
 * step. A build from a working tree therefore names the PREVIOUS release, which is honest: nothing
 * has been published for the tree in hand.
 *
 * <p>No framework, no configuration system, nothing but a {@code Properties} load, and no
 * main-scope dependency: a consumer that pins the CLI must not inherit a classpath for it.
 */
public final class PlatformAccessCliBinary {

  /** The resource the build filters {@code ${project.version}} into, beside this class. */
  private static final String RESOURCE = "platform-access-cli-binary.properties";

  /**
   * The name the binary is published under in the artifacts store's {@code daemons} surface —
   * {@code /artifacts/daemons/qits-platform-access-cli/<version>} — and the name qits-ci's step
   * downloads by.
   *
   * <p>Named here rather than at the call site because it is the same string this repository's own
   * release recipe writes: {@code .config/qits/ci-event-release.yml} declares {@code {type: daemon,
   * name: qits-platform-access-cli}} and PUTs to that path. A rename that moved only one side is
   * the failure this constant removes, and {@code PlatformAccessCliBinaryTest} asserts the literal.
   *
   * <p>It is this repository's name and not the command's, which is the split qits-ci-daemon's
   * publish already records: the repository name is where the recipe lives, {@link #COMMAND} is
   * what the file is called once it is on a {@code PATH}.
   */
  public static final String DAEMON_NAME = "qits-platform-access-cli";

  /** What the downloaded binary is called on {@code PATH}. */
  public static final String COMMAND = "qits";

  /** The released version: the {@code daemons} store coordinate this jar was published beside. */
  public static final String VERSION = readVersion();

  private static String readVersion() {
    Properties p = new Properties();
    try (InputStream in = PlatformAccessCliBinary.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        // Not recoverable and deliberately not defaulted: a consumer that silently downloaded ""
        // or "latest" is the exact failure this class exists to remove.
        throw new IllegalStateException(
            RESOURCE
                + " is not on the classpath beside "
                + PlatformAccessCliBinary.class.getName());
      }
      p.load(in);
    } catch (IOException e) {
      throw new IllegalStateException("cannot read " + RESOURCE, e);
    }
    String version = p.getProperty("version", "");
    if (version.isBlank() || version.startsWith("$")) {
      // `$` catches the one mistake that would otherwise ship: resource filtering switched off,
      // leaving the literal `${project.version}` to be used as a download path segment.
      throw new IllegalStateException(RESOURCE + " carries no resolved version: '" + version + "'");
    }
    return version;
  }

  private PlatformAccessCliBinary() {}
}
