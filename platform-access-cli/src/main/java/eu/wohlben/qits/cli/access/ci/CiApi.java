package eu.wohlben.qits.cli.access.ci;

import com.fasterxml.jackson.databind.JsonNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The qits-ci run API ({@code /ci/api/runs}), read as JSON trees, like {@code ProjectsApi}: the
 * service adds fields and grows its word lists (statuses, trigger types), and a tree needs no
 * reflection in the native binary.
 */
public final class CiApi {

    private final PlatformClient client;
    private final String base;

    public CiApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /**
     * {@code {"runs":[…]}}: one repository's runs, newest first, without step output. The
     * repository and a count are the only filters the service has; a null limit asks for all.
     */
    public JsonNode runs(String repositoryId, Integer limit) throws CliFailure, InterruptedException {
        String query = "?repositoryId=" + URLEncoder.encode(repositoryId, StandardCharsets.UTF_8)
                + (limit == null ? "" : "&limit=" + limit);
        return client.get(uri("/ci/api/runs" + query));
    }

    /** One run with its steps and their output, and while it runs, the step in flight ({@code live}). */
    public JsonNode run(String runId) throws CliFailure, InterruptedException {
        return client.get(uri("/ci/api/runs/" + segment(runId)));
    }

    /** {@code {"runId":…}}: the new run, queued. HTTP 409 when the run has not finished. */
    public JsonNode retry(String runId) throws CliFailure, InterruptedException {
        return client.post(uri("/ci/api/runs/" + segment(runId) + "/retry"));
    }

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable ci address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
