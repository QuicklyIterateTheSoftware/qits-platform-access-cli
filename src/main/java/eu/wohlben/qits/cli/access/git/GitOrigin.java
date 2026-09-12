package eu.wohlben.qits.cli.access.git;

import java.net.URI;
import java.util.Locale;

/**
 * A sign-in belongs to one Git HTTP origin, never to every host a person uses. Copied from
 * qits-bootstrap-cli rather than shared through a jar.
 */
public final class GitOrigin {

    private GitOrigin() {
    }

    /** {@code scheme://host[:port]}, lower case, without a default port or a path. */
    public static String normalize(String raw) {
        URI uri;
        try {
            uri = URI.create(raw == null ? "" : raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("the git host must be an http:// or https:// address, not '" + raw + "'");
        }
        if (uri.getScheme() == null || uri.getHost() == null || !(uri.getScheme().equalsIgnoreCase("http")
                || uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("the git host must be an http:// or https:// address, not '" + raw + "'");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        int port = uri.getPort();
        boolean defaultPort = port == -1 || (scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443);
        return scheme + "://" + (host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host)
                + (defaultPort ? "" : ":" + port);
    }

    /** The origin of a Git credential request: its {@code protocol} and {@code host} lines. */
    public static String fromGitRequest(String protocol, String host) {
        if (protocol == null || host == null || protocol.isBlank() || host.isBlank()) {
            throw new IllegalArgumentException("Git named no HTTP host");
        }
        return normalize(protocol + "://" + host);
    }
}
