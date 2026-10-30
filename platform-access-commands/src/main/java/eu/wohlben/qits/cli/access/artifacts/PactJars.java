package eu.wohlben.qits.cli.access.artifacts;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * The jars of qits-artifacts' hosted maven repository, read with the command's own credential: a
 * consumer's pact package, {@code <consumer>-pacts-<provider>}, at one version. Through {@link
 * PlatformClient}, so a CI step presents its run's token and a workstation its session, as for every
 * other read.
 */
public final class PactJars {

    /** A group segment, an artifact id or a version: no slash, no traversal, nothing to escape. */
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private final PlatformClient client;
    private final String root;

    /** @param root the hosted maven repository, {@code https://registry.qits.wohlben.eu/artifacts/maven/maven} */
    public PactJars(PlatformClient client, String root) {
        this.client = client;
        this.root = root;
    }

    /** The jar of {@code groupId:artifactId:version}, written to {@code target}, which must exist. */
    public void download(String groupId, String artifactId, String version, Path target)
            throws CliFailure, InterruptedException {
        client.download(address(groupId, artifactId, version), target);
    }

    /** {@code <root>/<group with dots as slashes>/<artifact>/<version>/<artifact>-<version>.jar}. */
    URI address(String groupId, String artifactId, String version) throws CliFailure {
        StringBuilder path = new StringBuilder(root);
        for (String segment : groupId.split("\\.", -1)) {
            path.append('/').append(segment("group id", groupId, segment));
        }
        path.append('/').append(segment("artifact id", artifactId, artifactId));
        path.append('/').append(segment("version", version, version));
        path.append('/').append(artifactId).append('-').append(version).append(".jar");
        try {
            URI uri = URI.create(path.toString());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + root + "' is not a usable registry address.", CliFailure.USAGE);
        }
    }

    /** Whether {@code value} is one plain path segment, as a coordinate part must be. */
    static boolean plainSegment(String value) {
        return value != null && SEGMENT.matcher(value).matches() && !value.contains("..");
    }

    private static String segment(String what, String whole, String value) throws CliFailure {
        if (!plainSegment(value)) {
            throw new CliFailure("The " + what + " '" + whole + "' is not a plain maven coordinate.",
                    CliFailure.FAILED);
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
