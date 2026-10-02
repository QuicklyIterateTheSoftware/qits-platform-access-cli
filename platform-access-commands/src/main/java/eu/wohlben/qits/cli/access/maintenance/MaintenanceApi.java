package eu.wohlben.qits.cli.access.maintenance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** The qits-maintenance API ({@code /maintenance/api}), read as JSON trees like {@code CiApi}. */
public final class MaintenanceApi {

    private final PlatformClient client;
    private final String base;

    public MaintenanceApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code {"id":…}}: the bump that renders one release request's screenshot baselines. HTTP 409
     * when the request takes no branch or one is already running for it.
     */
    public JsonNode screenshotBaselines(String repositoryName, String requestId, String workItem)
            throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (workItem != null && !workItem.isBlank()) {
            body.put("workItem", workItem.strip());
        }
        return client.post(uri("/maintenance/api/repositories/" + segment(repositoryName) + "/release-requests/"
                + segment(requestId) + "/screenshot-baselines"), body);
    }

    /** One bump: its mode, status, message and the commit it left. */
    public JsonNode bump(String id) throws CliFailure, InterruptedException {
        return client.get(uri("/maintenance/api/bumps/" + segment(id)));
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable maintenance address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
