package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where a service is, in either home. The one place that decides it.
 * <p>
 * Outside, a service is a public vhost behind the edge — {@code https://projects.dev.wohlben.eu} —
 * derived from the session's own idp address. Inside the platform those names do not resolve at
 * all: the container network answers wire aliases, and nothing else. That is a second set of
 * addresses, not a second code path, which is why both live here.
 * <p>
 * Every service is a rule: {@code http://<env>-qits-<app>:8080}, where {@code <env>} comes from
 * {@code QITS_ENV}, else from the host of whichever platform URL the container carries, else
 * {@code dev}.
 * <p>
 * <b>There was a table beside that rule and it has been deleted.</b> A platform-plane service
 * answered on a bare alias that no pattern described — the idp on {@code qits-platform-idp}, the bus
 * on {@code qits-events} — so the two had to be written down. The epic <i>Remove the platform
 * service concept</i> deleted that plane, and every one of those aliases stopped resolving; the
 * table went on handing them out. {@code qits events} therefore reconnect-looped forever against
 * {@code http://qits-events:8080} in every agent container on the estate, which is the cost of a
 * copy of somebody else's fact.
 * <p>
 * Keeping the rule in one class was the reason this class exists, and it is why the deletion is one
 * edit rather than a search across the commands.
 */
public final class PlatformEndpoints {

    /** The port every service listens on inside the container network. */
    static final int WIRE_PORT = 8080;

    static final String ENVIRONMENT = "QITS_ENV";
    static final String DAEMON_URL = "QITS_WORKSPACE_DAEMON_URL";
    static final String PROJECTS_DAEMON_URL = "QITS_PROJECTS_DAEMON_URL";
    static final String REPOSITORY_MCP_URL = "QITS_REPOSITORY_MCP_URL";

    /**
     * The platform URLs a container is injected with, in the order the tier is read off them. Two
     * kinds of container run this binary and they carry different variables: a workspace container
     * carries {@code QITS_WORKSPACE_DAEMON_URL}, and a project-agent container — triage, epic
     * refinement, the composed run for an epic — carries {@code QITS_PROJECTS_DAEMON_URL} instead.
     * Both carry {@code QITS_REPOSITORY_MCP_URL}, which is why it is last and is the one that
     * always answers. The order is fixed so the same container always resolves the same tier.
     */
    static final List<String> TIER_URLS = List.of(DAEMON_URL, PROJECTS_DAEMON_URL, REPOSITORY_MCP_URL);

    /** What a workspace container is told the idp's token endpoint is. */
    static final String TOKEN_URL = "QITS_GIT_AUTH_TOKEN_URL";

    static final String DEFAULT_ENVIRONMENT = "dev";

    private final Mode mode;
    private final Map<String, String> env;
    private final String idpUrl;

    /**
     * @param idpUrl the session's idp address, which only a workstation has; null inside
     */
    public PlatformEndpoints(Mode mode, Map<String, String> env, String idpUrl) {
        this.mode = mode;
        this.env = env;
        this.idpUrl = idpUrl;
    }

    /**
     * The base URL of {@code app} — {@code projects}, {@code ci}, {@code idp} — without its path
     * prefix. {@code QITS_URL_<APP>} overrides it in either home, which is the escape hatch when a
     * name moves.
     */
    public String base(String app) throws CliFailure {
        return base(app, "Cannot tell the " + app + " address from the idp address " + idpUrl
                + ". Set QITS_URL_" + app.toUpperCase(Locale.ROOT) + ".");
    }

    /**
     * @param refusal what to say when a workstation cannot work the address out — the caller knows
     *                which of its own flags and variables would have said it
     */
    public String base(String app, String refusal) throws CliFailure {
        String override = env.get("QITS_URL_" + app.toUpperCase(Locale.ROOT));
        if (override != null && !override.isBlank()) {
            return trim(override);
        }
        return mode.inPlatform() ? wire(app) : vhost(app, refusal);
    }

    /**
     * {@code http://dev-qits-projects:8080}. One rule for every application, because there is one
     * kind of service.
     * <p>
     * <b>The idp is the one address read rather than derived, and only when the container was told
     * one.</b> {@code QITS_GIT_AUTH_TOKEN_URL} is injected at container creation and names the idp
     * this platform mints at, so believing it costs nothing and covers the case the rule cannot: an
     * idp reached somewhere other than its wire alias. It is preferred over the rule rather than
     * used as a fallback, which is deliberate — a container carrying an explicit address is a
     * container somebody addressed on purpose.
     * <p>
     * That preference has a cost worth naming: a container created BEFORE an address moved carries
     * the old one, and its environment is frozen at creation. So a stale container keeps dialling a
     * name that has gone while a fresh one is correct, and the symptom is per-container rather than
     * per-version. {@code QITS_URL_IDP} overrides both.
     */
    String wire(String app) {
        if ("idp".equals(app)) {
            String told = origin(env.get(TOKEN_URL));
            if (told != null) {
                return told;
            }
        }
        return "http://" + environment() + "-qits-" + app + ":" + WIRE_PORT;
    }

    /** {@code https://projects.dev.wohlben.eu}, from the session's own idp address. */
    String vhost(String app, String refusal) throws CliFailure {
        URI idp = uri(idpUrl);
        String host = idp == null ? null : idp.getHost();
        if (idp == null || idp.getScheme() == null || host == null || !host.startsWith("idp.")) {
            throw new CliFailure(refusal, CliFailure.USAGE);
        }
        return idp.getScheme() + "://" + app + host.substring("idp".length())
                + (idp.getPort() == -1 ? "" : ":" + idp.getPort());
    }

    /**
     * The environment's label: {@code dev}. Inside, it is the first label of a wire alias —
     * {@code ws://dev-qits-workspaces:8080/…} is {@code dev} — read off the first of
     * {@link #TIER_URLS} the container carries. A value that is not a wire alias is passed over
     * rather than believed. {@code QITS_ENV} comes before all of them: it stays the one variable an
     * operator can set to settle the tier explicitly.
     */
    public String environment() {
        String told = env.get(ENVIRONMENT);
        if (told != null && !told.isBlank()) {
            return told.strip();
        }
        for (String variable : TIER_URLS) {
            String label = firstLabel(env.get(variable));
            if (label != null) {
                return label;
            }
        }
        return DEFAULT_ENVIRONMENT;
    }

    /** {@code http://dev-qits-projects:8080/projects/mcp} is {@code dev}; anything else is null. */
    private static String firstLabel(String url) {
        URI uri = uri(url);
        String host = uri == null ? null : uri.getHost();
        int dash = host == null ? -1 : host.indexOf('-');
        return dash > 0 ? host.substring(0, dash) : null;
    }

    /** {@code http://qits-platform-idp:8080/idp/token} is {@code http://qits-platform-idp:8080}. */
    private static String origin(String url) {
        URI uri = uri(url);
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return null;
        }
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static URI uri(String value) {
        try {
            return value == null || value.isBlank() ? null : URI.create(value.strip());
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
    }

    private static String trim(String url) {
        String stripped = url.strip();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }
}
