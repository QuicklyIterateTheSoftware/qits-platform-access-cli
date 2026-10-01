package eu.wohlben.qits.cli.access.idp;

import java.util.Map;

/**
 * Which platform: {@code --idp-url}, else {@code QITS_IDP_URL}, else {@code
 * https://idp.qits.<QITS_DOMAIN>/idp}, else {@value #DEFAULT}, the same default install.sh writes.
 * <p>
 * The platform is the project {@value #PROJECT}, and it has no environments, so a public name is
 * {@code <app>.qits.<domain>} and nothing else. {@code QITS_ENV_NAME} is not read: there is no
 * environment left for it to name.
 * <p>
 * Not from the idp's discovery document: that names the idp's INTERNAL issuer
 * ({@code http://qits-platform-idp:8080/idp}), which no workstation can reach.
 */
public final class IdpUrl {

    /** The platform's own project slug, the label between the app and the domain. */
    static final String PROJECT = "qits";
    static final String DEFAULT = "https://idp." + PROJECT + ".wohlben.eu/idp";

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
        if (!domain.isEmpty()) {
            return "https://idp." + PROJECT + "." + domain + "/idp";
        }
        return DEFAULT;
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
