package eu.wohlben.qits.cli.access.waits;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * An agent's waits, or a project's desk's, in qits-workspaces ({@code
 * /workspaces/api/agents/{agentId}/waits} or {@code /workspaces/api/desks/{projectId}/waits}, qits-1153
 * W8, qits-1618), read as JSON trees like the other services' answers. Both doors take the same
 * request and answer the same shape; only the path differs, by who holds the waits.
 */
final class WaitsApi {

    private final PlatformClient client;
    private final String base;

    WaitsApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * Who a wait belongs to: one agent, or one project's desk (qits-1618). Never both; {@link
     * WaitCommand#holder} is the one place that decides which.
     */
    sealed interface Holder {

        record Agent(String id) implements Holder {
        }

        record Desk(String projectId) implements Holder {
        }

        static Holder agent(String id) {
            return new Agent(id);
        }

        static Holder desk(String projectId) {
            return new Desk(projectId);
        }
    }

    /** {@code {label?, selection, timeout?}} in; the new wait out (201). */
    JsonNode create(Holder holder, JsonNode body) throws CliFailure, InterruptedException {
        return client.post(uri(path(holder)), body);
    }

    /** The holder's waits, in the state given, or in every state when it is null. */
    JsonNode list(Holder holder, String state) throws CliFailure, InterruptedException {
        return client.get(uri(path(holder) + (state == null ? "" : "?state=" + segment(state))));
    }

    /** Cancels one wait: the wait as it now stands, or nothing (204). */
    JsonNode cancel(Holder holder, String waitId) throws CliFailure, InterruptedException {
        return client.delete(uri(path(holder) + "/" + segment(waitId)));
    }

    static String path(Holder holder) {
        return switch (holder) {
            case Holder.Agent agent -> "/workspaces/api/agents/" + segment(agent.id()) + "/waits";
            case Holder.Desk desk -> "/workspaces/api/desks/" + segment(desk.projectId()) + "/waits";
        };
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
