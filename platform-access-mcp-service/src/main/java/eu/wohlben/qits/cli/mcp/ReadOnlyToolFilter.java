package eu.wohlben.qits.cli.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The read-only mode: a request whose URL carries {@code agentReadOnly=true} sees and runs only the
 * tools whose command is marked {@code Effect.READ}. The coding-agents library adds the marker to the
 * qits server's URL for a run nobody watches, so an agent steered by text it read (a commit message,
 * a ticket) cannot change anything.
 * <p>
 * <b>One check for both doors.</b> quarkus-mcp-server asks a {@link ToolFilter} for {@code tools/list}
 * and again for {@code tools/call}, on every request, with that request's own URL. A streamable-HTTP
 * client sends every request to the URL it was given, query included, so the marker cannot be left
 * behind after the listing. A write called anyway is a tool the library does not find: it answers
 * the JSON-RPC error it gives an unknown tool, and nothing runs. The server's instructions say why.
 * <p>
 * <b>It never throws.</b> The library logs a filter that throws and then lets the tool through, so a
 * failure here answers false instead.
 * <p>
 * <b>It fails closed.</b> A command nobody marked is a write. A marker with any value but
 * {@code false} is read-only. A request this filter cannot read counts as read-only too.
 */
@Singleton
public class ReadOnlyToolFilter implements ToolFilter {

    /** The query parameter that asks for the read-only mode. */
    public static final String READ_ONLY_PARAM = "agentReadOnly";

    /** Looked up when asked, not injected: McpTools needs the tool manager, which needs this filter. */
    @Inject
    Instance<McpTools> tools;

    @Inject
    Instance<HttpServerRequest> request;

    @Override
    public boolean test(ToolInfo tool, FilterContext context) {
        // Never throw: the library logs a filter that throws and then treats it as a pass.
        try {
            return tools.get().isRead(tool.name()) || !readOnly();
        } catch (RuntimeException unknown) {
            return false;
        }
    }

    /** Whether this request asks for the read-only mode; true when it cannot be read. */
    boolean readOnly() {
        try {
            List<String> values = request.get().params().getAll(READ_ONLY_PARAM);
            return values.stream().anyMatch(v -> !"false".equalsIgnoreCase(v.strip()));
        } catch (RuntimeException unreadable) {
            return true;
        }
    }
}
