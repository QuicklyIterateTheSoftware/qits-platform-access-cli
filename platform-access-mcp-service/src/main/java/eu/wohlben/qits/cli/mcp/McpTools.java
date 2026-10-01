package eu.wohlben.qits.cli.mcp;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.mcp.ToolCatalog.Tool;
import eu.wohlben.qits.cli.session.RequestCredential;
import io.quarkiverse.mcp.server.InitialResponseInfo;
import io.quarkiverse.mcp.server.TextContent;
import io.quarkiverse.mcp.server.ToolManager;
import io.quarkiverse.mcp.server.ToolResponse;
import io.quarkus.runtime.Startup;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The tools of the `qits` server: the catalog registered with the server at startup, each call run
 * by {@link ToolRunner} as the caller, and the server's {@code instructions}, which list what is not
 * served and why.
 * <p>
 * <b>The guard is armed before anything else.</b> {@link CliContext#MCP_SERVICE} is set as the first
 * thing this bean does, before a single tool exists, so no call can ever run while a command could
 * still fall back to this process's own environment.
 * <p>
 * A handler registered with {@code setHandler} runs on a worker thread, not the event loop, and that
 * is required: every command makes blocking HTTP calls.
 */
@Startup
@Singleton
public class McpTools implements InitialResponseInfo {

    /** The server name, as {@code quarkus.mcp.server.qits.*} spells it. */
    public static final String SERVER = "qits";

    private static final Logger LOG = Logger.getLogger(McpTools.class);

    @Inject
    ToolManager toolManager;

    @Inject
    JsonWebToken caller;

    @Inject
    Config config;

    private ToolCatalog catalog;
    private ToolRunner runner;

    @PostConstruct
    void register() {
        System.setProperty(CliContext.MCP_SERVICE, "true");
        catalog = ToolCatalog.of(new CommandLine(new AccessCli()));
        runner = new ToolRunner(factory -> new CommandLine(new AccessCli(), factory),
                ToolRunner.environment(name -> config.getOptionalValue(name, String.class).orElse(null)),
                Clock.systemUTC());
        for (Tool tool : catalog.tools()) {
            toolManager.newTool(tool.name())
                    .setServerName(SERVER)
                    .setDescription(tool.description())
                    // The builder's own arguments cannot say enum or array; the schema is set whole.
                    .setInputSchema(new JsonObject(tool.inputSchema()))
                    .setHandler(arguments -> call(tool, arguments.args()))
                    .register();
        }
        LOG.infof("Serving %d tools; %d commands are not served.", catalog.tools().size(), catalog.excluded().size());
    }

    public ToolCatalog catalog() {
        return catalog;
    }

    private ToolResponse call(Tool tool, Map<String, Object> arguments) {
        String token = caller.getRawToken();
        if (token == null || token.isBlank()) {
            // The route is authenticated, so this is a configuration fault, never a caller's: say so
            // rather than run the command as nobody.
            return ToolResponse.error("This call carried no bearer; nothing was run.");
        }
        ToolRunner.Result result = runner.run(tool, arguments, new RequestCredential(token));
        List<TextContent> content = result.texts().stream().map(TextContent::new).toList();
        return new ToolResponse(result.error(), content);
    }

    @Override
    public Optional<String> instructions(String serverName) {
        return SERVER.equals(serverName) ? Optional.of(catalog.instructions()) : Optional.empty();
    }
}
