package eu.wohlben.qits.cli.access.deployments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-deployments settings doors ({@code /deployments/api/settings}, epic qits-765): the
 * per-application, per-environment values the deployer hands a service, read as JSON trees like
 * every platform answer.
 * <p>
 * A secret's {@code value} is null in every answer: the service never sends one back.
 */
public final class DeploymentsApi {

    private final PlatformClient client;
    private final String base;

    public DeploymentsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code {settings:[{application, environment, key, value, secret, updatedAt, updatedBy}]}}: the
     * application's settings, of every environment unless {@code environment} names one.
     */
    public JsonNode settings(String application, String environment) throws CliFailure, InterruptedException {
        String query = "?application=" + query(application)
                + (environment == null || environment.isBlank() ? "" : "&environment=" + query(environment));
        return client.get(uri("/deployments/api/settings" + query));
    }

    /** The stored row, its value null when it is a secret. The body is {@code {"value", "secret"}}. */
    public JsonNode putSetting(String application, String environment, String key, String value, boolean secret)
            throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("value", value);
        body.put("secret", secret);
        return client.put(uri(settingPath(application, environment, key)), body);
    }

    /** Nothing (204); 404 when no such setting is stored. */
    public JsonNode deleteSetting(String application, String environment, String key)
            throws CliFailure, InterruptedException {
        return client.delete(uri(settingPath(application, environment, key)));
    }

    /**
     * {@code {application, environment, added:[{key, value, secret}], removed:[{key, value, secret}],
     * changed:[{key, from, to, secret}], kept:[{key, value, secret}], unchanged}}: what the next
     * deployment of the application in the environment would hand it that the running one does not
     * have. {@code extras} asks for {@code ?extras=include}: the values the running deployment has
     * that no setting holds, listed under {@code kept}.
     */
    public JsonNode previewSettings(String application, String environment, boolean extras)
            throws CliFailure, InterruptedException {
        return client.get(uri("/deployments/api/settings/preview/" + segment(application) + "/" + segment(environment)
                + (extras ? "?extras=include" : "")));
    }

    private static String settingPath(String application, String environment, String key) {
        return "/deployments/api/settings/" + segment(application) + "/" + segment(environment) + "/" + segment(key);
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable deployments address.", CliFailure.USAGE);
        }
    }

    private static String query(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
