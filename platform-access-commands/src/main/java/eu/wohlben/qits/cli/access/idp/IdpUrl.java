package eu.wohlben.qits.cli.access.idp;

import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.session.PlatformEndpoints;

import java.util.Map;

/**
 * Which platform: {@code --idp-url}, else {@code QITS_IDP_URL}, else {@code
 * https://idp.qits.<QITS_DOMAIN>/idp}. With none of the three there is no platform to name, and
 * this refuses rather than guess one install's domain.
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
    public static final String PROJECT = "qits";

    private IdpUrl() {
    }

    /**
     * The idp base URL without a trailing slash.
     *
     * @throws CliFailure a usage failure when no flag, no {@code QITS_IDP_URL} and no {@code
     *                    QITS_DOMAIN} names a platform, or the domain is no public one
     */
    public static String resolve(String flag, Map<String, String> env) throws CliFailure {
        if (!blank(flag)) {
            return trim(flag);
        }
        String configured = env.get("QITS_IDP_URL");
        if (!blank(configured)) {
            return trim(configured);
        }
        if (blank(env.get("QITS_DOMAIN"))) {
            throw new CliFailure("No platform named: pass --idp-url, or set QITS_IDP_URL or QITS_DOMAIN "
                    + "(the idp is https://idp." + PROJECT + ".<QITS_DOMAIN>/idp).", CliFailure.USAGE);
        }
        return "https://idp." + PROJECT + "." + PlatformEndpoints.domain(env) + "/idp";
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
