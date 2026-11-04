package eu.wohlben.qits.cli.session;

import eu.wohlben.qits.cli.access.platform.CliFailure;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Where a service is, in every home. The one place that decides it.
 * <p>
 * <b>Every home dials the public edge</b> — {@code https://<app>.qits.<QITS_DOMAIN>} — and none of
 * them dials a wire alias any more (qits-1436). A workstation derives the domain from its session's
 * own idp address; a workspace handed a token of its own ({@link Mode#EDGE_TOKEN}) and the MCP
 * service's in-platform commands ({@link Mode#IN_PLATFORM}) read it from {@code QITS_DOMAIN}.
 * <p>
 * <b>The in-platform home used to be a second set of addresses</b>: {@code http://<env>-qits-<app>:8080},
 * with {@code <env>} read off whichever platform URL the container carried. The address plane those
 * aliases belong to is being retired (qits-1408), and a copy of somebody else's addressing rule is
 * exactly what once left {@code qits events} reconnect-looping against an alias that had stopped
 * resolving. One rule now, the edge's, for every home.
 * <p>
 * Keeping the rule in one class was the reason this class exists, and it is why the deletion is one
 * edit rather than a search across the commands.
 */
public final class PlatformEndpoints {

    /** An operator's explicit environment label, read by {@link #environment()}. */
    static final String ENVIRONMENT = "QITS_ENV";

    static final String DEFAULT_ENVIRONMENT = "dev";

    /** The estate's public domain, {@code <QITS_DOMAIN>}: what the token home's vhosts hang off. */
    static final String DOMAIN = "QITS_DOMAIN";

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
     * prefix. {@code QITS_URL_<APP>} overrides it in every home, which is the escape hatch when a
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
        if (mode.edgeToken() || mode.inPlatform()) {
            return edge(app, env);
        }
        return vhost(app, refusal);
    }

    /**
     * The landing app, {@code https://qits.<QITS_DOMAIN>}: the bare platform domain, where a person's
     * browser opens a work item. Inside the platform it comes from {@code QITS_DOMAIN}, as every
     * address there does, and without one there is none to give.
     * {@code QITS_URL_LANDING} overrides it, as it does every address.
     */
    public String landing() throws CliFailure {
        String override = env.get("QITS_URL_LANDING");
        if (override != null && !override.isBlank()) {
            return trim(override);
        }
        if (mode == Mode.WORKSTATION) {
            String projects = vhost("projects", "Cannot tell the landing address from the idp address " + idpUrl
                    + ". Set QITS_URL_LANDING.");
            return projects.replaceFirst("://projects\\.", "://");
        }
        return "https://qits." + domain(env);
    }

    /**
     * {@code https://projects.qits.<QITS_DOMAIN>}, from {@code QITS_DOMAIN}: the shape of {@link
     * #vhost}, fed from the environment instead of a session.
     * <p>
     * A domain that cannot be a public one is refused rather than dialled. Blank or undotted gives a
     * host nothing resolves, and {@code *.localhost} is a name curl and the JVM send to the loopback
     * whatever DNS says — in the token home that is the workspace's own container, not the platform.
     */
    public static String edge(String app, Map<String, String> env) throws CliFailure {
        return "https://" + app + ".qits." + domain(env);
    }

    /** {@code <QITS_DOMAIN>}, or a usage failure naming {@code QITS_DOMAIN}. */
    public static String domain(Map<String, String> env) throws CliFailure {
        String told = env.get(DOMAIN);
        String domain = told == null ? "" : told.strip();
        while (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        String lower = domain.toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || !domain.contains(".") || lower.endsWith(".localhost")) {
            throw new CliFailure(DOMAIN + " is " + (domain.isEmpty() ? "not set" : "'" + domain + "'")
                    + ", which is not a public domain the edge answers on. A workspace token (" + Mode.TOKEN
                    + ") reaches the platform at https://<app>.qits.<" + DOMAIN + ">.", CliFailure.USAGE);
        }
        return domain;
    }

    /** {@code https://projects.qits.<QITS_DOMAIN>}, from the session's own idp address. */
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
     * The environment's label, for display only: {@code QITS_ENV} when an operator states it, else
     * {@code dev}. No address is composed from it any more — the label used to be read off a wire
     * alias the container carried, and no address is a wire alias now.
     */
    public String environment() {
        String told = env.get(ENVIRONMENT);
        if (told != null && !told.isBlank()) {
            return told.strip();
        }
        return DEFAULT_ENVIRONMENT;
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
