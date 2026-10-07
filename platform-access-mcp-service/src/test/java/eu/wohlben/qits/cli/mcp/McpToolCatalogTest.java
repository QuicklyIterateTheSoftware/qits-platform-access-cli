package eu.wohlben.qits.cli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.CliFailure;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.mcp.ToolCatalog.Tool;
import eu.wohlben.qits.cli.session.RequestCredential;
import eu.wohlben.qits.cli.tui.api.Input;
import eu.wohlben.qits.cli.tui.api.Interaction;
import eu.wohlben.qits.cli.tui.api.TuiCommand;
import eu.wohlben.qits.cli.tui.api.TuiCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contract between the CLI and the MCP server, over the real {@code AccessCli} tree: every
 * command that runs is a tool unless its nearest {@code @TuiCommand(interaction)} says otherwise, and
 * a tool's schema is its options. A new option or command on the CLI cannot be missing here, and one
 * that should not be served cannot slip in, without this test failing.
 * <p>
 * The calls run through {@link ToolRunner} against {@link FakePlatform}, the library's own fake
 * platform, with {@link CliContext#MCP_SERVICE} set as the service sets it: a command that fell back
 * to the process's own context would throw rather than call the fake.
 */
class McpToolCatalogTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The strictest tool-name rule among the clients in use. */
    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** An agent's bearer, as the service hands it on: the signature is the service's to check, not the command's. */
    private static final String AGENT = token("""
            {"sub":"workspace-7f3a","aud":["qits-platform"],"groups":["qits:agent"]}""");

    private static final String QITS = "0a0b0c0d-0000-4000-8000-000000000001";

    private static String previousGuard;

    private final ToolCatalog catalog = ToolCatalog.of(new CommandLine(new AccessCli()));
    private FakePlatform platform;
    private final CountDownLatch closing = new CountDownLatch(1);

    @BeforeAll
    static void armTheGuard() {
        previousGuard = System.getProperty(CliContext.MCP_SERVICE);
        System.setProperty(CliContext.MCP_SERVICE, "true");
    }

    @AfterAll
    static void disarmTheGuard() {
        if (previousGuard == null) {
            System.clearProperty(CliContext.MCP_SERVICE);
        } else {
            System.setProperty(CliContext.MCP_SERVICE, previousGuard);
        }
    }

    @BeforeEach
    void start() throws Exception {
        platform = new FakePlatform();
    }

    @AfterEach
    void stop() {
        closing.countDown();
        platform.close();
    }

    private static String token(String claims) {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    // --- the tree --------------------------------------------------------------------------------

    /** Every command that does something when named last, by path, with the nearest declaration above it. */
    private static Map<String, CommandSpec> runnable(CommandSpec spec, String path, Map<String, CommandSpec> found) {
        Map<String, CommandSpec> children = new LinkedHashMap<>();
        for (CommandLine child : spec.subcommands().values()) {
            children.putIfAbsent(child.getCommandSpec().name(), child.getCommandSpec());
        }
        if (!path.isEmpty() && (children.isEmpty() || spec.userObject() instanceof java.util.concurrent.Callable<?>)) {
            found.put(path, spec);
        }
        children.values().forEach(child -> runnable(child, path.isEmpty() ? child.name() : path + "_" + child.name(), found));
        return found;
    }

    private static Map<String, CommandSpec> realTree() {
        return runnable(new CommandLine(new AccessCli()).getCommandSpec(), "", new LinkedHashMap<>());
    }

    private static Interaction nearest(CommandSpec spec) {
        for (CommandSpec at = spec; at != null; at = at.parent()) {
            TuiCommand declared = TuiCommands.declaredOn(at.userObject());
            if (declared != null) {
                return declared.interaction();
            }
        }
        return Interaction.PLAIN;
    }

    private Tool tool(String name) {
        return catalog.tool(name).orElseThrow(() -> new AssertionError("no tool " + name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> property(Tool tool, String name) {
        return (Map<String, Object>) properties(tool).get(name);
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(Tool tool) {
        return (List<String>) tool.inputSchema().getOrDefault("required", List.of());
    }

    @Test
    void everyCommandIsExcludedExactlyWhenItsNearestDeclarationSaysSo() {
        Map<String, CommandSpec> commands = realTree();
        assertThat(commands).isNotEmpty();
        Set<String> excludedCommands = new TreeSet<>();
        catalog.excluded().forEach(e -> excludedCommands.add(e.command().substring("qits ".length()).replace(' ', '_')));

        commands.forEach((name, spec) -> {
            Interaction interaction = nearest(spec);
            boolean excluded = interaction != Interaction.PLAIN;
            assertThat(excludedCommands.contains(name)).as(name + " excluded, nearest declaration " + interaction)
                    .isEqualTo(excluded);
            assertThat(catalog.tool(name).isPresent()).as(name + " is a tool").isEqualTo(!excluded);
        });
        assertThat(catalog.tools().size() + catalog.excluded().size()).isEqualTo(commands.size());
    }

    @Test
    void theExclusionsAreTheOnesTheEpicNamesWithTheirReasons() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));
        assertThat(byCommand).containsKeys("qits login", "qits git-login", "qits session-daemon",
                "qits checkout-daemon", "qits events", "qits observe", "qits git-credential", "qits mcp-credential",
                "qits help skill");
        assertThat(byCommand.keySet()).anySatisfy(command -> assertThat(command).startsWith("qits artifacts publish"));
        assertThat(byCommand.get("qits login").reason()).isEqualTo("needs a browser and a person");
        assertThat(byCommand.get("qits events").reason()).isEqualTo("runs until stopped");
        assertThat(byCommand.get("qits mcp-credential").reason()).isEqualTo("only means something on the caller's machine");
        byCommand.values().stream().filter(e -> e.command().startsWith("qits artifacts publish"))
                .forEach(e -> assertThat(e.reason()).isEqualTo("CI-step only, reads local files"));

        String instructions = catalog.instructions();
        catalog.excluded().forEach(e -> assertThat(instructions).contains(e.line()));
        assertThat(instructions).contains("Not served here (" + catalog.excluded().size() + ")");
    }

    @Test
    void theReportSubmitIsCiOnlyAndTheReportShowIsATool() {
        Map<String, ToolCatalog.Exclusion> byCommand = new HashMap<>();
        catalog.excluded().forEach(e -> byCommand.put(e.command(), e));

        assertThat(byCommand.get("qits ci report submit")).isNotNull();
        assertThat(byCommand.get("qits ci report submit").reason()).isEqualTo("CI-step only, reads local files");
        assertThat(catalog.tool("ci_report_submit")).isEmpty();
        assertThat(catalog.tool("ci_report")).isEmpty();
        assertThat(properties(tool("ci_report_show"))).containsKeys("run-id", "kind", "project", "repository", "output");
        assertThat(required(tool("ci_report_show"))).containsExactly("run-id");
    }

    @Test
    void theQueriesAreToolsUnderTheirStreamsAndTheLocalCommandsAreNot() {
        assertThat(catalog.tool("events_query")).isPresent();
        assertThat(catalog.tool("observe_query")).isPresent();
        assertThat(catalog.tool("events")).isEmpty();
        assertThat(catalog.tool("observe")).isEmpty();
        assertThat(catalog.tool("mcp-credential")).isEmpty();
        assertThat(catalog.tool("git-credential")).isEmpty();
        assertThat(catalog.tool("login")).isEmpty();
        assertThat(catalog.tool("help_skill")).isEmpty();
        assertThat(catalog.tools()).extracting(Tool::name)
                .contains("projects_list", "work_update", "work_comment_create", "release-request_join", "ci_runs",
                        "repositories_create");
    }

    @Test
    void everyToolsSchemaIsItsOptionsByTheRules() {
        Map<String, CommandSpec> commands = realTree();
        for (Tool tool : catalog.tools()) {
            CommandSpec spec = commands.get(tool.name());
            Set<String> expected = new LinkedHashSet<>();
            Set<String> expectedRequired = new TreeSet<>();
            for (OptionSpec option : spec.options()) {
                if (option.usageHelp() || option.versionHelp() || option.longestName().endsWith("-url")) {
                    continue;
                }
                String name = option.longestName().replaceFirst("^-+", "");
                expected.add(name);
                if (option.required()) {
                    expectedRequired.add(name);
                }
                Map<String, Object> schema = property(tool, name);
                assertThat(schema).as(tool.name() + " " + name).isNotNull();
                assertThat(schema.get("type")).as(tool.name() + " " + name).isEqualTo(expectedType(option.type()));
                if (option.defaultValue() != null && !name.equals("output") && !"array".equals(schema.get("type"))) {
                    assertThat(String.valueOf(schema.get("default"))).as(tool.name() + " " + name + " default")
                            .isEqualTo(option.defaultValue());
                }
                if (option.description().length > 0) {
                    assertThat(schema.get("description")).as(tool.name() + " " + name).isNotNull();
                }
            }
            for (PositionalParamSpec positional : spec.positionalParameters()) {
                String name = positional.paramLabel().replaceAll("[<>]", "").strip().replaceAll("\\s+", "-");
                expected.add(name);
                if (positional.arity().min() >= 1) {
                    expectedRequired.add(name);
                }
            }
            if (TuiCommands.inputOf(spec.userObject()) == Input.PAYLOAD) {
                expected.add(ToolCatalog.PAYLOAD);
            }
            assertThat(properties(tool).keySet()).as(tool.name()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(new TreeSet<>(required(tool))).as(tool.name() + " required").isEqualTo(expectedRequired);
        }
    }

    private static String expectedType(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            return "integer";
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        return "string";
    }

    @Test
    void inheritedOptionsShowUpOnTheirLeaves() {
        for (String leaf : List.of("work_list", "work_details", "work_create", "work_update", "work_transition",
                "work_status", "work_comment_create", "work_comment_update")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("entity", "output");
        }
        for (String leaf : List.of("ci_runs", "ci_run", "ci_retry")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "repository", "output");
        }
        for (String leaf : List.of("release-request_list", "release-request_create", "release-request_join",
                "release-request_withdraw")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "repository", "output");
        }
        for (String leaf : List.of("repositories_list", "repositories_create")) {
            assertThat(properties(tool(leaf))).as(leaf).containsKeys("project", "output");
        }
    }

    @Test
    void noAddressIsEverAnArgumentAndNoNameBreaksTheClientRule() {
        for (Tool tool : catalog.tools()) {
            assertThat(tool.name()).matches(NAME);
            assertThat(properties(tool).keySet()).as(tool.name())
                    .noneSatisfy(name -> assertThat(name).endsWith("-url"))
                    .doesNotContain("help", "version")
                    .allSatisfy(name -> assertThat(name).matches("^[A-Za-z0-9_.-]{1,64}$"));
        }
    }

    @Test
    void thePayloadIsOnExactlyTheCommandsThatReadOne() {
        Map<String, CommandSpec> commands = realTree();
        Set<String> marked = new TreeSet<>();
        commands.forEach((name, spec) -> {
            if (TuiCommands.inputOf(spec.userObject()) == Input.PAYLOAD) {
                marked.add(name);
            }
        });
        assertThat(marked).containsExactlyInAnyOrder("work_create", "work_update", "work_transition", "work_status",
                "work_comment_create", "work_comment_update");
        for (Tool tool : catalog.tools()) {
            boolean payload = marked.contains(tool.name());
            assertThat(tool.payload()).as(tool.name()).isEqualTo(payload);
            assertThat(properties(tool).containsKey(ToolCatalog.PAYLOAD)).as(tool.name()).isEqualTo(payload);
            assertThat(tool.description().endsWith(ToolCatalog.PAYLOAD_HINT)).as(tool.name()).isEqualTo(payload);
        }
        assertThat(property(tool("work_update"), ToolCatalog.PAYLOAD)).containsEntry("type", "object");
    }

    @Test
    void theDescriptionIsTheUsageTextAndTheOutputDefaultsToJson() {
        Tool runs = tool("ci_runs");
        assertThat(runs.description()).startsWith(new CommandLine(new AccessCli()).getSubcommands().get("ci")
                .getSubcommands().get("runs").getCommandSpec().usageMessage().description()[0].replace("%n", "\n"));
        // The footer as a person reads it: under its heading, every example indented, the first too.
        assertThat(tool("projects_list").description()).contains("\n\nExamples:\n  qits projects list\n");
        assertThat(runs.outputJson()).isEqualTo("--output");
        assertThat(property(runs, "output")).containsEntry("default", "json");
        assertThat(property(runs, "limit")).containsEntry("type", "integer").containsEntry("default", 20L);
        assertThat(property(tool("events_query"), "output")).containsEntry("default", "json");
        assertThat(property(tool("observe_query"), "filter")).containsEntry("type", "array");
        assertThat(required(tool("observe_query"))).contains("filter");
        assertThat(property(tool("ci_run"), "logs")).containsEntry("type", "boolean");
        assertThat(required(tool("ci_run"))).contains("run-id");
    }

    // --- a command nobody has written yet -----------------------------------------------------------

    enum Shade {LIGHT, DARK}

    /** Exists only here. It prints what it was handed, so a call shows the runner gave it the caller. */
    @CommandLine.Command(name = "fictional", description = "Not a real command.", footer = "  qits fictional --thing x")
    static class FictionalCommand extends PlatformCommand {

        @CommandLine.Option(names = "--thing", required = true, description = "The thing it needs.")
        String thing;

        @CommandLine.Option(names = "--times", defaultValue = "1", description = "How often.")
        int times;

        @CommandLine.Option(names = "--shade", description = "Which shade.")
        Shade shade;

        @CommandLine.Option(names = "--tag", description = "Tags.")
        List<String> tags;

        @CommandLine.Option(names = "--loud", description = "A flag.")
        boolean loud;

        @CommandLine.Option(names = "--elsewhere-url", description = "An address.")
        String elsewhere;

        @CommandLine.Parameters(index = "0", paramLabel = "<the subject>", description = "What about.")
        String subject;

        @Override
        protected int execute(CliContext context) throws CliFailure, InterruptedException {
            context.out().println(String.join("|", thing, String.valueOf(times), String.valueOf(shade),
                    String.valueOf(tags), String.valueOf(loud), subject, context.credential().who(),
                    String.valueOf(context.mode())));
            return 0;
        }
    }

    private static CommandLine withFictional(CommandLine.IFactory factory) {
        CommandLine cli = new CommandLine(new AccessCli(), factory);
        cli.addSubcommand(FictionalCommand.class);
        return cli;
    }

    @Test
    void aCommandThisTestInventedIsAToolWithNoOtherChange() {
        ToolCatalog withIt = ToolCatalog.of(withFictional(CommandLine.defaultFactory()));
        Tool fictional = withIt.tool("fictional").orElseThrow();
        assertThat(fictional.description()).startsWith("Not a real command.").contains("qits fictional --thing x");
        assertThat(properties(fictional).keySet()).containsExactlyInAnyOrder("thing", "times", "shade", "tag", "loud",
                "the-subject");
        assertThat(required(fictional)).containsExactlyInAnyOrder("thing", "the-subject");
        assertThat(property(fictional, "shade")).containsEntry("enum", List.of("LIGHT", "DARK"));
        assertThat(property(fictional, "tag")).containsEntry("type", "array");
        assertThat(property(fictional, "times")).containsEntry("default", 1L);
        assertThat(withIt.tools()).hasSize(catalog.tools().size() + 1);

        ToolRunner runner = new ToolRunner(McpToolCatalogTest::withFictional, ToolRunner.environment(n -> null),
                Clock.systemUTC());
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("thing", "a value; rm -rf /");
        arguments.put("times", 3);
        arguments.put("shade", "DARK");
        arguments.put("tag", List.of("one", "-two"));
        arguments.put("loud", true);
        arguments.put("the-subject", "--looks-like-an-option");
        ToolRunner.Result result = runner.run(fictional, arguments, new RequestCredential(AGENT));
        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(result.texts().getFirst().strip()).isEqualTo(
                "a value; rm -rf /|3|DARK|[one, -two]|true|--looks-like-an-option|agent (qits:agent)|IN_PLATFORM");
    }

    @Test
    void anArgumentTheToolDoesNotHaveIsAUsageErrorAndNothingRuns() {
        ToolRunner.Result result = runner().run(tool("projects_list"), Map.of("projects-url", "http://evil"),
                new RequestCredential(AGENT));
        assertThat(result.error()).isTrue();
        assertThat(result.texts()).containsExactly("Unknown argument 'projects-url' for projects_list.", "exit 2");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aParseErrorIsAResultWithPicocliMessageAndExit2() {
        ToolRunner.Result result = runner().run(tool("ci_runs"), Map.of("limit", "many"), new RequestCredential(AGENT));
        assertThat(result.error()).isTrue();
        assertThat(result.texts()).hasSize(2);
        assertThat(result.texts().get(0)).contains("--limit").contains("many");
        assertThat(result.texts().get(1)).isEqualTo("exit 2");
    }

    // --- calls against the fake platform -------------------------------------------------------------

    private ToolRunner runner() {
        Map<String, String> config = Map.of("QITS_ENV", "dev", "QITS_PROJECTS_URL", platform.url(),
                "QITS_EVENTS_URL", platform.url(), "QITS_OBSERVABILITY_URL", platform.url(),
                "QITS_CI_URL", platform.url());
        return new ToolRunner(factory -> new CommandLine(new AccessCli(), factory),
                ToolRunner.environment(config::get), Clock.systemUTC());
    }

    @Test
    void aReadAnswersWithTheServicesJsonAsTheCaller() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(QITS));

        ToolRunner.Result result = runner().run(tool("projects_list"), Map.of(), new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(result.texts()).hasSize(1);
        JsonNode answer = JSON.readTree(result.texts().getFirst());
        assertThat(answer.toString()).contains("qits platform");
        assertThat(platform.requests("GET", "/projects/api/projects")).singleElement()
                .satisfies(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    @Test
    void aRefusedWriteIsAnErrorResultInTheCredentialsOwnWords() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}}]}
                """.formatted(QITS));
        platform.answer("POST", "/projects/api/projects/" + QITS + "/repositories", 403, "{\"message\":\"forbidden\"}");

        ToolRunner.Result result = runner().run(tool("repositories_create"),
                Map.of("project", "qits", "name", "qits-docs-app"), new RequestCredential(AGENT));

        assertThat(result.error()).isTrue();
        assertThat(result.texts().get(0)).contains("403 - this credential is qits:agent, which reads but does not write");
        assertThat(result.texts().get(1)).isEqualTo("exit 1");
        assertThat(platform.requests("POST", "/projects/api/projects/" + QITS + "/repositories")).singleElement()
                .satisfies(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    private static final String THREAD = "/projects/api/work/qits-100/comments";
    private static final String DOCUMENT = """
            {"openapi":"3.1.0",
             "components":{"schemas":{
               "CreateCommentRequest":{"type":"object","required":["body"],
                 "properties":{"body":{"type":"string"}}}}},
             "paths":{
               "/projects/api/work/{qualifiedId}/comments":{
                 "post":{"requestBody":{"required":true,"content":{"application/json":
                   {"schema":{"$ref":"#/components/schemas/CreateCommentRequest"}}}}}}}}
            """;

    @Test
    void aPayloadWriteSendsThePayloadAsTheCommandsStdin() throws Exception {
        platform.answer("POST", THREAD, """
                {"comment":{"id":"c3","entityId":"e","author":"agent","body":"hi",
                  "createdAt":"2026-09-29T10:00:00Z","updatedAt":"2026-09-29T10:00:00Z"}}
                """);
        Map<String, Object> payload = Map.of("body", "I can reproduce it", "note", Map.of("kept", List.of(1, 2)));

        ToolRunner.Result result = runner().run(tool("work_comment_create"),
                Map.of("entity", "qits-100", "payload", payload), new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        FakePlatform.Request sent = platform.requests("POST", THREAD).getFirst();
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.valueToTree(payload));
        assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT);
        assertThat(JSON.readTree(result.texts().getFirst()).path("comment").path("id").asText()).isEqualTo("c3");
    }

    @Test
    void aPayloadWriteCalledWithoutOneAnswersWithTheSchemaAndSendsNothing() throws Exception {
        platform.answer("GET", "/projects/q/openapi", DOCUMENT);

        ToolRunner.Result result = runner().run(tool("work_comment_create"), Map.of("entity", "qits-100"),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).path("required").toString()).isEqualTo("[\"body\"]");
        assertThat(platform.requests("POST", THREAD)).isEmpty();
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theEventsQueryReturnsWhileTheLiveStreamNeverEnds() throws Exception {
        // A live stream that never ends: a query that touched it would hang here.
        platform.streams.add(platform.stream(true, ": keepalive"));
        platform.answer("GET", "/events/api/events", "{\"events\":[],\"nextCursor\":null}");

        ToolRunner.Result result = runner().run(tool("events_query"), Map.of("since", "15m"),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).has("events")).isTrue();
        assertThat(platform.requests("GET", "/events/api/stream")).isEmpty();
        assertThat(platform.requests("GET", "/events/api/events")).isNotEmpty()
                .allSatisfy(sent -> assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT));
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void theObserveQueryReturnsWhileTheLiveStreamNeverEnds() throws Exception {
        // The live socket's handshake never answers: a query that opened it would hang here.
        platform.route("GET", "/observability/stream", request -> {
            try {
                closing.await();
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
            return new FakePlatform.Answer(500, "", Map.of());
        });
        platform.answer("POST", "/observability/api/telemetry/records/search",
                "{\"records\":[],\"truncated\":false,\"bufferedSince\":\"2020-01-01T00:00:00Z\"}");

        ToolRunner.Result result = runner().run(tool("observe_query"), Map.of("filter", List.of("kind=log level>=ERROR")),
                new RequestCredential(AGENT));

        assertThat(result.error()).as(result.text()).isFalse();
        assertThat(JSON.readTree(result.texts().getFirst()).has("records")).isTrue();
        assertThat(platform.requests("GET", "/observability/stream")).isEmpty();
        FakePlatform.Request sent = platform.requests("POST", "/observability/api/telemetry/records/search").getFirst();
        assertThat(sent.authorization()).isEqualTo("Bearer " + AGENT);
        assertThat(sent.body()).contains("kind");
    }

    @Test
    void theServicesOwnContextIsNeverReachedForTheGuardIsArmed() {
        assertThat(System.getProperty(CliContext.MCP_SERVICE)).isNotNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(CliContext::system)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theCommandsSeeTheServicesEnvironmentAndNothingElse() {
        Map<String, String> env = ToolRunner.environment(Map.of("QITS_ENV", "prod", "QITS_COMMISSIONED_CLIENT_ID", "x",
                "HOME", "/root", "QITS_OBSERVABILITY_URL", "http://prod-qits-observability:8080")::get);
        assertThat(env).containsExactlyInAnyOrderEntriesOf(Map.of("QITS_PLATFORM", "true", "QITS_ENV", "prod",
                "QITS_OBSERVABILITY_URL", "http://prod-qits-observability:8080"));
    }

    @Test
    void theResultMapping() {
        assertThat(ToolRunner.result(0, "out", "")).isEqualTo(new ToolRunner.Result(false, List.of("out"), 0));
        assertThat(ToolRunner.result(0, "out", "a note")).isEqualTo(new ToolRunner.Result(false, List.of("out", "a note"), 0));
        assertThat(ToolRunner.result(1, "", "refused")).isEqualTo(new ToolRunner.Result(true, List.of("refused", "exit 1"), 1));
        assertThat(ToolRunner.result(3, "half", "broke"))
                .isEqualTo(new ToolRunner.Result(true, List.of("broke", "exit 3", "half"), 3));
    }
}
