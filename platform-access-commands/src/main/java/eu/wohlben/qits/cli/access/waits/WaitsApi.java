package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * An agent's waits in qits-workspaces ({@code /workspaces/api/agents/{agentId}/waits}, qits-1153 W8),
 * read as JSON trees like the other services' answers.
 */
final class WaitsApi {

    private final PlatformClient client;
    private final String base;

    WaitsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** {@code {label?, selection, timeout?}} in; the new wait out (201). */
    JsonNode create(String agentId, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(path(agentId)), body);
    }

    /** The agent's waits, in the state given, or in every state when it is null. */
    JsonNode list(String agentId, String state) throws CliFailure, InterruptedException {
        return client.get(uri(path(agentId) + (state == null ? "" : "?state=" + segment(state))));
    }

    /** Cancels one wait: the wait as it now stands, or nothing (204). */
    JsonNode cancel(String agentId, String waitId) throws CliFailure, InterruptedException {
        return client.delete(uri(path(agentId) + "/" + segment(waitId)));
    }

    static String path(String agentId) {
        return "/workspaces/api/agents/" + segment(agentId) + "/waits";
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable workspaces address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
