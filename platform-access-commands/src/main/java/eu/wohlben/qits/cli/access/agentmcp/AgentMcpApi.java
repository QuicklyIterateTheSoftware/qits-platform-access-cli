package eu.wohlben.qits.cli.access.agentmcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The credential doors of qits-projects' agent MCP catalog ({@code
 * /projects/api/agent-mcp-catalog/credentials}, epic qits-765, qits-1394): the secrets the catalog's
 * MCP servers are reached with. No answer ever holds a value.
 */
public final class AgentMcpApi {

    private final PlatformClient client;
    private final String base;

    public AgentMcpApi(PlatformClient client, String base) {
        this.client = client;
        this.base = base;
    }

    /** {@code {credentials:[{key, updatedAt, updatedBy}]}}, ordered by key: which keys are stored, never their values. */
    public JsonNode credentials() throws CliFailure, InterruptedException {
        return client.get(uri(PATH));
    }

    /** {@code {key, updatedAt, updatedBy}}, never the value. The body is {@code {"value"}}; 400 for a key that is not {@code env.<VAR>}. */
    public JsonNode putCredential(String key, String value) throws CliFailure, InterruptedException {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("value", value);
        return client.put(uri(PATH + "/" + segment(key)), body);
    }

    /** Nothing (204); 404 when no credential of the key is stored. */
    public JsonNode deleteCredential(String key) throws CliFailure, InterruptedException {
        return client.delete(uri(PATH + "/" + segment(key)));
    }

    private static final String PATH = "/projects/api/agent-mcp-catalog/credentials";

    private URI uri(String path) throws CliFailure {
        try {
            URI uri = URI.create(base + path);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("no scheme or host");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new CliFailure("'" + base + "' is not a usable projects address.", CliFailure.USAGE);
        }
    }

    private static String segment(String value) {
        return URLEncoder.encode(value.strip(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
