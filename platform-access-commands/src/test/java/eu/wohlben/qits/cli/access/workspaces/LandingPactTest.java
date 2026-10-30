package eu.wohlben.qits.cli.access.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CLI asks qits-workspaces exactly what the landing app asks (qits-1261): every request the
 * landing app's workspace pages recorded in their consumer pacts, with qits-workspaces and with a
 * slot's daemon through the container proxy, is made by one `qits` command, with the same method,
 * path, query and body.
 * <p>
 * The two pact files under {@code workspaces/landing-pacts/} are copies of qits-landing-app's
 * {@code pacts/}; copy them again when the pages change. Each interaction names the call it records
 * ({@code qits-call.operationId}), and {@link #ROWS} says which command makes it, read from the
 * recorded request. A call no row covers fails here, unless it is one of {@link #NOT_OFFERED}, with
 * the reason there.
 */
class LandingPactTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The landing app's calls the CLI leaves out on purpose. {@code getAgentActiveProcess} has no
     * command yet: nothing in this epic asked for one. The daemon ops qits-landing-app's Chat and
     * Files tabs reach through the agent proxy (`getCommandLog`, `listFiles`, `readFile`, `detect`)
     * are recorded here at the daemon's own, slot-addressed shape (the pact's own javadoc), which
     * only the deprecated `qits workspaces agents chat|harness|files` aliases of epic qits-1667 ever
     * reproduced; those are gone (qits-1749), and `qits agents chat|files` reach the same operations
     * through the agent proxy instead, which qits-workspaces' contract records no row for
     * ({@code WorkspacesContract}'s {@code AGENT_PROXY} exclusion) because it is forwarded raw.
     */
    static final Set<String> NOT_OFFERED = Set.of("getAgentActiveProcess", "getCommandLog", "listFiles", "readFile",
            "detect");

    /** The command that makes each call, built from the request the landing app recorded. */
    static final Map<String, Function<JsonNode, List<String>>> ROWS = new HashMap<>();

    static {
        // --- slots ---
        ROWS.put("listWorkspaces", r -> List.of("workspaces", "list", "--repository", query(r, "repositoryId")));
        ROWS.put("getWorkspace", r -> List.of("workspaces", "show", segment(r, 3)));
        ROWS.put("getWorkspaceActiveProcess", r -> List.of("workspaces", "active-process", segment(r, 3)));
        ROWS.put("ensureWorkspaceContainer", r -> List.of("workspaces", "start", segment(r, 3)));
        ROWS.put("stopWorkspaceContainer", r -> List.of("workspaces", "stop", segment(r, 3)));
        ROWS.put("recreateWorkspaceContainer", r -> List.of("workspaces", "recreate", segment(r, 3)));
        ROWS.put("listOpenWorkspaces", r -> List.of("workspaces", "items"));
        ROWS.put("listWorkItemWorkspaces", r -> List.of("workspaces", "history", segment(r, 3)));
        ROWS.put("listWorkspaceAgents", r -> List.of("workspaces", "show", segment(r, 3)));
        // --- agents (qits agents, epic qits-1667) ---
        ROWS.put("listAgents", r -> {
            List<String> argv = new ArrayList<>(List.of("agents", "list"));
            if (query(r, "workId") != null) {
                argv.addAll(List.of("--work", query(r, "workId")));
            }
            if (query(r, "workspaceId") != null) {
                argv.addAll(List.of("--workspace", query(r, "workspaceId")));
            }
            if (query(r, "state") != null) {
                argv.addAll(List.of("--state", query(r, "state")));
            }
            if (query(r, "projectId") != null) {
                argv.addAll(List.of("--project", query(r, "projectId")));
            }
            return argv;
        });
        ROWS.put("getAgent", r -> List.of("agents", "show", segment(r, 3)));
        ROWS.put("promptAgent", r -> List.of("agents", "prompt", segment(r, 3), body(r).path("text").asText()));
        ROWS.put("interruptAgent", r -> List.of("agents", "interrupt", segment(r, 3)));
        ROWS.put("stopAgent", r -> List.of("agents", "stop", segment(r, 3)));
        ROWS.put("restartAgent", r -> List.of("agents", "restart", segment(r, 3)));
        ROWS.put("discardAgent", r -> body(r).path("force").asBoolean()
                ? List.of("agents", "discard", segment(r, 3), "--force")
                : List.of("agents", "discard", segment(r, 3)));
        ROWS.put("getAgentPromptDraft", r -> List.of("agents", "draft", "show", segment(r, 3)));
        ROWS.put("saveAgentPromptDraft", r -> List.of("agents", "draft", "save", segment(r, 3),
                body(r).path("serializedPrompt").asText()));
        ROWS.put("deleteAgentPromptDraft", r -> List.of("agents", "draft", "discard", segment(r, 3)));
        // --- a slot's container, through the container proxy ---
        ROWS.put("refinePrompt", r -> List.of("workspaces", "agents", "refine", "--workspace", segment(r, 2),
                "--preamble", body(r).path("preamble").asText(), body(r).path("transcript").asText()));
        ROWS.put("availableAgents", r -> List.of("workspaces", "agents", "sign-in", "status",
                "--workspace", segment(r, 2)));
        ROWS.put("openSignInTerminal", r -> body(r).has("agentType")
                ? List.of("workspaces", "agents", "sign-in", "open", "--workspace", segment(r, 2),
                        "--harness", body(r).path("agentType").asText())
                : List.of("workspaces", "agents", "sign-in", "open", "--workspace", segment(r, 2)));
        ROWS.put("listPlugins", r -> List.of("workspaces", "agents", "plugins", "list", "--workspace", segment(r, 2)));
        ROWS.put("installPlugin", r -> List.of("workspaces", "agents", "plugins", "install", segment(r, 4),
                "--workspace", segment(r, 2)));
    }

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        new SessionFile(home.resolve("qits")).write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0",
                T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));
    }

    @AfterEach
    void stop() {
        if (platform != null) {
            platform.close();
        }
        idp.close();
    }

    @Test
    void everyCallTheLandingAppsWorkspacePagesMakeIsMadeByACommandTheSameWay() throws Exception {
        Set<String> covered = new TreeSet<>();
        Set<String> skipped = new TreeSet<>();
        int checked = 0;
        for (String file : List.of("qits-landing-app_qits-workspaces-service.json",
                "qits-landing-app_qits-workspace-daemon.json")) {
            JsonNode pact = read(file);
            assertThat(pact.path("consumer").path("name").asText()).isEqualTo("qits-landing-app");
            for (JsonNode interaction : pact.path("interactions")) {
                String operation = interaction.path("comments").path("references").path("qits-call")
                        .path("operationId").asText();
                if (NOT_OFFERED.contains(operation)) {
                    skipped.add(operation);
                    continue;
                }
                Function<JsonNode, List<String>> row = ROWS.get(operation);
                assertThat(row).as("a command for the landing app's " + operation + " (" + file + ")").isNotNull();
                check(interaction, row.apply(interaction.path("request")));
                covered.add(operation);
                checked++;
            }
        }
        assertThat(skipped).isEqualTo(NOT_OFFERED);
        assertThat(covered).isEqualTo(ROWS.keySet());
        assertThat(checked).isGreaterThan(30);
    }

    private void check(JsonNode interaction, List<String> argv) throws Exception {
        if (platform != null) {
            platform.close();
        }
        platform = new FakePlatform();
        JsonNode request = interaction.path("request");
        JsonNode response = interaction.path("response");
        String method = request.path("method").asText();
        String path = request.path("path").asText();
        // What a command reads before the recorded call: a work item looked up by its placeholder id,
        // or --project resolved to the project's uuid.
        for (String placeholder : List.of("{bugTicketId}", "{ticketId}", "{improvementTicketId}")) {
            platform.answer("GET", "/projects/api/work/" + placeholder,
                    "{\"id\":\"" + placeholder + "\",\"qualifiedId\":\"qits-1\",\"archetype\":\"TICKET\"}");
        }
        String projectId = query(request, "projectId");
        if (projectId != null) {
            platform.answer("GET", "/projects/api/projects",
                    "{\"entries\":[{\"project\":{\"id\":\"" + projectId + "\",\"slug\":\"p\",\"name\":\"p\"}}]}");
        }
        // listWorkspaceAgents: `qits workspaces show` reads the slot itself first.
        platform.answer("GET", "/workspaces/api/workspaces/1", "{\"workspace\":{\"id\":1,\"runtimeStatus\":\"RUNNING\"}}");
        JsonNode answer = content(response.path("body"));
        platform.answer(method, path, response.path("status").asInt(), answer == null ? "" : answer.toString());

        Map<String, String> env = new HashMap<>();
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_WORKSPACES_URL", platform.url());
        env.put("QITS_PROJECTS_URL", platform.url());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, argv.toArray(String[]::new));
        String said = String.join(" ", argv) + " -> " + exit + "\n" + out + err;
        assertThat(exit).as(said).isNotEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8))
                .doesNotContain(FakeIdp.SECRET);

        Map<String, List<String>> query = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = request.path("query").fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            List<String> values = new ArrayList<>();
            field.getValue().forEach(v -> values.add(v.asText()));
            query.put(field.getKey(), values);
        }
        JsonNode body = request.has("body") ? content(request.path("body")) : null;
        List<FakePlatform.Request> made = platform.requests(method, path);
        assertThat(made).as(said + "\nmade " + method + " " + path).isNotEmpty();
        assertThat(made).as(said).anySatisfy(sent -> {
            assertThat(decode(sent.query())).isEqualTo(query);
            assertThat(sent.authorization()).startsWith("Bearer ");
            if (body == null) {
                assertThat(sent.body()).isEmpty();
            } else {
                assertThat(sent.contentType()).startsWith("application/json");
                assertThat(JSON.readTree(sent.body())).isEqualTo(body);
            }
        });
    }

    private static JsonNode read(String file) throws Exception {
        try (InputStream in = LandingPactTest.class.getResourceAsStream("/workspaces/landing-pacts/" + file)) {
            assertThat(in).as(file).isNotNull();
            return JSON.readTree(in);
        }
    }

    /** A pact v4 body: {@code {content, contentType, encoded}}; null for none. */
    private static JsonNode content(JsonNode body) {
        if (body == null || body.isMissingNode() || body.isNull()) {
            return null;
        }
        JsonNode content = body.has("content") ? body.get("content") : body;
        return content.isNull() ? null : content;
    }

    private static JsonNode body(JsonNode request) {
        JsonNode content = content(request.path("body"));
        return content == null ? JSON.createObjectNode() : content;
    }

    private static String query(JsonNode request, String name) {
        JsonNode values = request.path("query").path(name);
        return values.isArray() && !values.isEmpty() ? values.get(0).asText() : null;
    }

    /** The path's segment at {@code index}, counted from the first after the leading slash. */
    private static String segment(JsonNode request, int index) {
        return request.path("path").asText().substring(1).split("/")[index];
    }

    private static Map<String, List<String>> decode(String raw) {
        Map<String, List<String>> query = new TreeMap<>();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
        }
        return query;
    }
}
