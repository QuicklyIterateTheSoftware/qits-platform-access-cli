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
     * {@code {requestId, foldSha, automations:[{kind, label, state, detail, bumpId, runIds, branch,
     * resultSha, updatedAt}]}}: the release request's release-request automations, at {@code foldSha}
     * when one is given, else the request's newest fold.
     */
    public JsonNode automations(String requestId, String foldSha) throws CliFailure, InterruptedException {
        String query = foldSha == null || foldSha.isBlank() ? ""
                : "?foldSha=" + URLEncoder.encode(foldSha.strip(), StandardCharsets.UTF_8);
        return client.get(uri("/maintenance/api/release-requests/" + segment(requestId) + "/automations" + query));
    }

    /**
     * {@code {"id":…}}: the bump that re-runs one kind on the request's current fold, skipping
     * carry-over and applicability. HTTP 404 for an unknown kind or repository; HTTP 409 when one is
     * already running for (request, kind), the request takes no branch, or bumping is off.
     */
    public JsonNode runAutomation(String requestId, String kind, String workItem) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        if (workItem != null && !workItem.isBlank()) {
            body.put("workItem", workItem.strip());
        }
        return client.post(uri("/maintenance/api/release-requests/" + segment(requestId) + "/automations/"
                + segment(kind) + "/runs"), body);
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
