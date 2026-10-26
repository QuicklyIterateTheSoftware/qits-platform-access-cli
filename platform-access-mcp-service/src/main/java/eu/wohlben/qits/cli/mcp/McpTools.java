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
import io.quarkus.vertx.runtime.jackson.QuarkusJacksonJsonCodec;
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
import java.util.stream.Collectors;

/**
 * The tools of the `qits` server: the catalog registered with the server at startup, each call run
 * by {@link ToolRunner} as the caller, and the server's {@code instructions}, which list what is not
 * served and why.
 * <p>
 * <b>The guard is armed before anything else.</b> {@link CliContext#MCP_SERVICE} is set as the first
 * thing this bean does, before a single tool exists, so no call can ever run while a command could
 * still fall back to this process's own environment.
 * <p>
 * A request whose URL carries {@code agentReadOnly=true} gets only the tools marked READ, and a call
 * to any other is refused: see {@link ReadOnlyToolFilter}.
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
    private java.util.Set<String> reads;

    @PostConstruct
    void register() {
        System.setProperty(CliContext.MCP_SERVICE, "true");
        // THE VERT.X JSON CODEC IS RESET BEFORE ANYTHING IS SERVED, and every tool result depends on
        // it (qits-1226). The MCP extension writes a result with JsonObject.mapFrom, which is Vert.x's
        // Json.CODEC, which is QuarkusJacksonJsonCodec: a static mapper that copies the CDI
        // ObjectMapper the first time it is used — the one McpObjectMapperCustomizer gave its
        // NON_NULL mixins — but only if Arc is up then, and a bare `new ObjectMapper()` if it is not.
        // This application carries no quarkus-rest-jackson, the only kind of extension that asks
        // for the codec to be initialised at run time, so the native image initialises it at BUILD
        // time; whenever something touches it during the image build, before Arc exists, the bare
        // mapper is frozen into the runner and every content item goes out as
        // `{"text":…,"_meta":null,"annotations":null,"type":"text"}` beside
        // `"structuredContent":null`, which MCP clients reject whole. Whether a build does that is
        // not up to us: 2026.1012.174516 froze it, 2026.1013.194420 from the same main code did not.
        // Here Arc is up and no request has been answered, so the next use copies the customised
        // mapper — the state of a good build, whatever the build did. McpWireJsonIT holds it against
        // the native runner the release ships.
        //
        // QuarkusJacksonJsonCodec is a Quarkus-internal runtime class, not an API: on a Quarkus
        // upgrade, check that reset() still exists and still means "copy the CDI mapper next time".
        QuarkusJacksonJsonCodec.reset();
        catalog = ToolCatalog.of(new CommandLine(new AccessCli()));
        reads = catalog.tools().stream().filter(Tool::read).map(Tool::name).collect(Collectors.toUnmodifiableSet());
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

    /**
     * Whether the tool's command is marked READ, the one thing {@link ReadOnlyToolFilter} asks. A
     * name this catalog does not hold is not a read.
     */
    public boolean isRead(String tool) {
        return reads != null && reads.contains(tool);
    }

    private ToolResponse call(Tool tool, Map<String, Object> arguments) {
        String token = caller.getRawToken();
        if (token == null || token.isBlank()) {
            // The route is authenticated, so this is a configuration fault, never a caller's: say so
            // rather than run the command as nobody.
            return ToolResponse.error("This call carried no bearer; nothing was run.");
        }
        ToolRunner.Result result = runner.run(tool, arguments, new RequestCredential(token),
                ToolRunner.callEnvironment(caller.getClaim(ToolRunner.AGENT_CLAIM),
                        caller.getClaim(ToolRunner.PROJECT_CLAIM), caller.getClaim(ToolRunner.CONTEXT_KIND_CLAIM),
                        caller.getGroups()));
        List<TextContent> content = result.texts().stream().map(TextContent::new).toList();
        return new ToolResponse(result.error(), content);
    }

    @Override
    public Optional<String> instructions(String serverName) {
        return SERVER.equals(serverName) ? Optional.of(catalog.instructions()) : Optional.empty();
    }
}
