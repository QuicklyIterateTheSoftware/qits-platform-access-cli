package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.session.Mode;
import eu.wohlben.qits.cli.session.PlatformEndpoints;

import java.net.URI;
import java.util.Map;

/**
 * Where a platform service is, as a base URL without its path prefix ({@code
 * https://projects.dev.wohlben.eu}). The one place that decides it.
 * <p>
 * Order: the command's flag, else {@code QITS_<APP>_URL}, else whatever {@link PlatformEndpoints}
 * works out — the public vhost on a workstation, the wire alias inside the platform. The commands
 * keep their own flags and variables, and the addresses themselves are decided in one class, so the
 * day the environment prefix goes away is one edit there.
 */
public final class PlatformUrls {

    private PlatformUrls() {
    }

    public static String projects(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("projects", "--projects-url", "QITS_PROJECTS_URL", flag, env, idpUrl);
    }

    public static String events(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("events", "--events-url", "QITS_EVENTS_URL", flag, env, idpUrl);
    }

    public static String observability(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("observability", "--observability-url", "QITS_OBSERVABILITY_URL", flag, env, idpUrl);
    }

    public static String ci(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("ci", "--ci-url", "QITS_CI_URL", flag, env, idpUrl);
    }

    /** The git host's origin, which Git pushes to through the edge. */
    public static String gitHost(String flag, Map<String, String> env, String idpUrl) throws CliFailure {
        return resolve("githost", "--git-host", "QITS_GIT_HOST_URL", flag, env, idpUrl);
    }

    /** The environment label of the idp's host: {@code dev} in {@code idp.dev.wohlben.eu}. */
    public static String environment(String idpUrl) throws CliFailure {
        String host = null;
        try {
            host = URI.create(idpUrl == null ? "" : idpUrl.strip()).getHost();
        } catch (IllegalArgumentException notAUrl) {
            // Refused below.
        }
        String rest = host != null && host.startsWith("idp.") ? host.substring("idp.".length()) : "";
        int dot = rest.indexOf('.');
        if (dot <= 0) {
            throw new CliFailure("Cannot tell the platform's environment from the idp address " + idpUrl
                    + ". Pass --audience (for example --audience dev-qits-githost).", CliFailure.USAGE);
        }
        return rest.substring(0, dot);
    }

    static String resolve(String app, String flagName, String variable, String flag, Map<String, String> env,
                          String idpUrl) throws CliFailure {
        if (!blank(flag)) {
            return trim(flag);
        }
        String configured = env.get(variable);
        if (!blank(configured)) {
            return trim(configured);
        }
        return new PlatformEndpoints(Mode.of(env), env, idpUrl).base(app,
                "Cannot tell the " + app + " address from the idp address " + idpUrl
                        + ". Pass " + flagName + " or set " + variable + ".");
    }

    private static String trim(String url) {
        String stripped = url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
