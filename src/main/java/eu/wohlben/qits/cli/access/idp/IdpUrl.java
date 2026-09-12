package eu.wohlben.qits.cli.access.idp;

import java.util.Map;

/**
 * Which platform: {@code --idp-url}, else {@code QITS_IDP_URL}, else derived from {@code
 * QITS_DOMAIN} and {@code QITS_ENV_NAME} the way qits-bootstrap-cli's login does it.
 * <p>
 * Not from the idp's discovery document: that names the idp's INTERNAL issuer
 * ({@code http://qits-platform-idp:8080/idp}), which no workstation can reach.
 */
public final class IdpUrl {

    private IdpUrl() {
    }

    /** The idp base URL without a trailing slash. */
    public static String resolve(String flag, Map<String, String> env) {
        if (!blank(flag)) {
            return trim(flag);
        }
        String configured = env.get("QITS_IDP_URL");
        if (!blank(configured)) {
            return trim(configured);
        }
        String domain = env.getOrDefault("QITS_DOMAIN", "").strip();
        String environment = env.getOrDefault("QITS_ENV_NAME", "").strip();
        if (!domain.isEmpty()) {
            // Every public name spells its environment, and with a domain no guess is safe: the
            // zone's wildcards resolve every name, so a wrong one reaches the right host and fails
            // with a confusing 404.
            if (environment.isEmpty()) {
                throw new IllegalArgumentException("QITS_DOMAIN is set to '" + domain + "' but QITS_ENV_NAME is not."
                        + " Set QITS_ENV_NAME to the platform's environment (for example dev), or pass --idp-url.");
            }
            return "https://idp." + environment + "." + domain + "/idp";
        }
        // A platform on this machine, behind the edge's port. It has always been called prod there.
        return "http://idp." + (environment.isEmpty() ? "prod" : environment) + ".localhost:8080/idp";
    }

    static String trim(String url) {
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
