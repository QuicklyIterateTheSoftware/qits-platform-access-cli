package eu.wohlben.qits.cli.access.update;

import eu.wohlben.qits.cli.access.publish.VersionOrder;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The version this binary was released as, built in when it was built: {@code ${project.version}},
 * filtered into {@code qits-version.properties} beside this class. The release stamps every pom of
 * the reactor with the version it tags and publishes the binary under, so this is the daemons-store
 * version of this very file. A build from a working tree names the release it was branched from.
 * <p>
 * A value the build did not fill in, or one that is not a release version ({@code 1.0-SNAPSHOT}),
 * is unknown, and a build that does not know its version never updates itself.
 */
public final class QitsVersion {

    /** What {@code qits -V} prints when the build carries no release version. */
    public static final String UNKNOWN = "unknown";

    private static final String RESOURCE = "qits-version.properties";

    /** {@code 2026.919.40352}: digits and dots, nothing else. */
    private static final Pattern RELEASE = Pattern.compile("[0-9]+(\\.[0-9]+)*");

    private static final Optional<String> CURRENT = read();

    private QitsVersion() {
    }

    /** This binary's version, or empty when it does not know one. */
    public static Optional<String> current() {
        return CURRENT;
    }

    /** Whether {@code version} is a released version: {@code YYYY.MMDD.HHMMSS}, no zero padding. */
    public static boolean isRelease(String version) {
        return version != null && version.length() <= 64 && RELEASE.matcher(version).matches();
    }

    /**
     * Whether {@code latest} is strictly newer than {@code running}, segment by segment as numbers
     * ({@code 2026.1015.1} is newer than {@code 2026.919.40352}). False when either is not a release
     * version, so nothing that is not one ever decides an update.
     */
    public static boolean newer(String latest, String running) {
        return isRelease(latest) && isRelease(running) && VersionOrder.compare(running, latest) == VersionOrder.BELOW;
    }

    /**
     * Gives every command under {@code root} the same {@code -V}. picocli does not hand a version
     * provider down to subcommands, so without this {@code qits work list -V} prints an empty line.
     * A command that names a version of its own keeps it.
     */
    public static CommandLine provideTo(CommandLine root) {
        CommandLine.Model.CommandSpec spec = root.getCommandSpec();
        if (spec.versionProvider() == null && spec.version().length == 0) {
            spec.versionProvider(new Provider());
        }
        root.getSubcommands().values().forEach(QitsVersion::provideTo);
        return root;
    }

    static Optional<String> read() {
        Properties properties = new Properties();
        try (InputStream in = QitsVersion.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Optional.empty();
            }
            properties.load(in);
        } catch (IOException | IllegalArgumentException unreadable) {
            return Optional.empty();
        }
        String version = properties.getProperty("version", "").strip();
        return isRelease(version) ? Optional.of(version) : Optional.empty();
    }

    /**
     * {@code qits -V}: the version alone on one line, so a program can compare it as it is —
     * {@code qits update} runs a downloaded binary with {@code -V} and wants exactly the version it
     * downloaded.
     */
    public static final class Provider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] {current().orElse(UNKNOWN)};
        }
    }
}
