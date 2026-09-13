package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** qits epic, as a person types it, in this process, against a fake platform and a fake idp. */
class EpicCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String EPICS = "/projects/api/projects/" + QITS + "/epics";
    private static final String TELEMETRY = "aaaa1111-0000-4000-8000-000000000001";
    private static final String DRILL = "aaaa2222-0000-4000-8000-000000000002";
    private static final String EVIL = "bbbb3333-0000-4000-8000-000000000003";
    private static final String NEW = "cccc4444-0000-4000-8000-000000000004";
    private static final String FEATURE = "dddd5555-0000-4000-8000-000000000005";
    private static final String TASK = "eeee6666-0000-4000-8000-000000000006";
    private static final char ESC = 0x1B;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();
    private final ByteArrayOutputStream allOut = new ByteArrayOutputStream();
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        SessionFile store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30))));

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}}]}
                """.formatted(QITS));
        // The third title carries escape sequences, as anyone who files an epic (or an agent) can write.
        platform.answer("GET", EPICS, """
                {"entries":[
                  {"epic":{"id":"%s","projectId":"%s","title":"Live telemetry","slug":"live-telemetry",
                    "status":"REFINING","supersededByEpicId":null,"description":"x",
                    "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T09:00:00Z","workspaces":[]}},
                  {"epic":{"id":"%s","projectId":"%s","title":"Drill down","slug":"drill-down",
                    "status":"IMPLEMENTATION","supersededByEpicId":null,"description":null,
                    "createdAt":"2026-09-12T09:10:00Z","updatedAt":"2026-09-12T09:20:00Z","workspaces":[]}},
                  {"epic":{"id":"%s","projectId":"%s","title":"Evil \\u001b]0;pwned\\u0007title","slug":"evil-title",
                    "status":"REFINING","supersededByEpicId":null,"description":null,
                    "createdAt":"2026-09-12T09:30:00Z","updatedAt":"2026-09-12T09:30:00Z","workspaces":[]}}]}
                """.formatted(TELEMETRY, QITS, DRILL, QITS, EVIL, QITS));
        platform.answer("GET", "/projects/api/epics/" + TELEMETRY, """
                {"epic":{"id":"%s","projectId":"%s","title":"Live telemetry","slug":"live-telemetry",
                  "status":"REFINING","supersededByEpicId":null,
                  "description":"Stream logs.\\n\\u001b[31mRed\\u001b[0m text\\u202e here\\r\\nThird line",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T09:00:00Z",
                  "workspaces":[{"workspaceRowId":7,"repositoryId":"r1","workspaceId":"ws-7","branch":"epic/live-telemetry"}]}}
                """.formatted(TELEMETRY, QITS));
        platform.answer("GET", "/projects/api/epics/" + TELEMETRY + "/features", """
                {"entries":[
                  {"feature":{"id":"%s","epicId":"%s","title":"Stream logs","slug":"stream-logs",
                    "description":"x","dependsOnFeatureId":null,"implementedOn":null,
                    "createdAt":"2026-09-12T09:01:00Z","updatedAt":"2026-09-12T09:01:00Z"}}]}
                """.formatted(FEATURE, TELEMETRY));
        platform.answer("GET", "/projects/api/features/" + FEATURE + "/tasks", """
                {"entries":[
                  {"task":{"id":"%s","featureId":"%s","repositoryId":"r1","title":"Wire the SSE endpoint",
                    "slug":"wire-the-sse-endpoint","description":null,"dependsOnTaskId":null,
                    "implementedAt":"2026-09-12T09:05:00Z",
                    "createdAt":"2026-09-12T09:02:00Z","updatedAt":"2026-09-12T09:05:00Z"}}]}
                """.formatted(TASK, FEATURE));
        platform.answer("POST", EPICS, """
                {"epic":{"id":"%s","projectId":"%s","title":"Live telemetry","slug":"live-telemetry",
                  "status":"REFINING","supersededByEpicId":null,"description":null,
                  "createdAt":"2026-09-13T10:00:00Z","updatedAt":"2026-09-13T10:00:00Z","workspaces":[]}}
                """.formatted(NEW, QITS));
        platform.answer("PUT", "/projects/api/epics/" + TELEMETRY, """
                {"epic":{"id":"%s","projectId":"%s","title":"Live telemetry v2","slug":"live-telemetry",
                  "status":"REFINING","supersededByEpicId":null,"description":"Stream logs.",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-13T10:00:00Z","workspaces":[]}}
                """.formatted(TELEMETRY, QITS));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result run(String... args) {
        return runWithInput(InputStream.nullInputStream(), args);
    }

    private Result runWithInput(InputStream in, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), in,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> { });
        CommandLine cli = new CommandLine(new AccessCli(), new CommandLine.IFactory() {
            @Override
            public <K> K create(Class<K> type) throws Exception {
                K made = CommandLine.defaultFactory().create(type);
                if (made instanceof PlatformCommand command) {
                    command.useContext(context);
                }
                return made;
            }
        });
        cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
        cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
        int exit = cli.execute(args);
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private JsonNode sentEpic(String path) throws Exception {
        return JSON.readTree(platform.requests("POST", path).getFirst().body());
    }

    // --- list ---

    @Test
    void listIsATableOldestFirst() {
        Result r = run("epic", "--project", "qits", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("ID").contains("SLUG").contains("STATUS").contains("TITLE").contains("UPDATED")
                .contains("live-telemetry").contains("REFINING").contains("Live telemetry")
                .contains("drill-down").contains("IMPLEMENTATION")
                .contains("evil-title").contains("Evil title").doesNotContain(String.valueOf(ESC));
        assertThat(platform.requests("GET", EPICS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", EPICS).getFirst().authorization())
                .isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
    }

    @Test
    void theStatusGoesToTheService() {
        Result r = run("epic", "--project", "qits", "list", "--status", "refining");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", EPICS).getFirst().query()).isEqualTo("status=REFINING");
    }

    @Test
    void jsonOutputEscapesControlCharacters() throws Exception {
        Result r = run("epic", "list", "--project", "qits", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out()).path("entries")).extracting(e -> e.path("epic").path("id").asText())
                .containsExactly(TELEMETRY, DRILL, EVIL);
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).containsIgnoringCase("\\u001b]0;pwned");
    }

    @Test
    void anEmptyListSaysWhatWasAskedFor() {
        platform.answer("GET", EPICS, "{\"entries\":[]}");
        assertThat(run("epic", "--project", "qits", "list").out()).isEqualTo("No epics in project qits.\n");
        assertThat(run("epic", "--project", "qits", "list", "--status", "ABANDONED").out())
                .isEqualTo("No ABANDONED epics in project qits.\n");
    }

    @Test
    void aStatusTheServiceDoesNotKnowIsRefused() {
        platform.answer("GET", EPICS, 400, "{\"message\":\"Unknown epic status: REFININGG\"}");

        Result r = run("epic", "--project", "qits", "list", "--status", "refiningg");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No epic status is called 'REFININGG' (HTTP 400). Statuses: REFINING, "
                + "IMPLEMENTATION, SUPERSEDED, ABANDONED.");
        assertThat(r.out()).isEmpty();
    }

    // --- new ---

    @Test
    void newSendsTitleAndPrintsTheEpic() throws Exception {
        Result r = run("epic", "--project", "qits", "new", "--title", " Live telemetry ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentEpic(EPICS)).isEqualTo(JSON.readTree("{\"title\":\"Live telemetry\"}"));
        assertThat(r.out()).startsWith("Epic " + NEW + "\n")
                .containsPattern("slug\\s+live-telemetry\\n")
                .containsPattern("status\\s+REFINING\\n")
                .contains("Description:\n  (none)\n")
                .doesNotContain("Features");
    }

    @Test
    void newSendsTheDescription() throws Exception {
        Result r = run("epic", "new", "--project", "qits", "--title", "Live telemetry",
                "--description", "Stream logs, spans and metrics.", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sentEpic(EPICS)).isEqualTo(
                JSON.readTree("{\"title\":\"Live telemetry\",\"description\":\"Stream logs, spans and metrics.\"}"));
        assertThat(JSON.readTree(r.out()).path("epic").path("id").asText()).isEqualTo(NEW);
    }

    @Test
    void newReadsTheDescriptionFromAFileAndFromStdin() throws Exception {
        Path plan = home.resolve("plan.md");
        Files.writeString(plan, "Steps:\n1. wire the endpoint\n\n", StandardCharsets.UTF_8);

        Result fromFile = run("epic", "--project", "qits", "new", "--title", "Live telemetry", "--description-file",
                plan.toString());
        assertThat(fromFile.exit()).as(fromFile.err()).isZero();
        assertThat(sentEpic(EPICS).path("description").asText()).isEqualTo("Steps:\n1. wire the endpoint");

        Result fromStdin = runWithInput(new ByteArrayInputStream("From a pipe\n".getBytes(StandardCharsets.UTF_8)),
                "epic", "--project", "qits", "new", "--title", "Live telemetry", "--description-file", "-");
        assertThat(fromStdin.exit()).as(fromStdin.err()).isZero();
    }

    @Test
    void newIsUsedWronglyWithoutCallingThePlatform() {
        assertThat(run("epic", "--project", "qits", "new").exit()).isEqualTo(2);

        Result blank = run("epic", "--project", "qits", "new", "--title", "  ");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(blank.err()).contains("--title must not be empty.");

        Result both = run("epic", "--project", "qits", "new", "--title", "Log",
                "--description", "a", "--description-file", "-");
        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("Give --description or --description-file, not both.");

        Result noProject = run("epic", "new", "--title", "Log");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        assertThat(platform.requests).isEmpty();
    }

    @Test
    void newWithoutTheRoleSaysSo() {
        platform.answer("POST", EPICS, 403, "");

        Result r = run("epic", "--project", "qits", "new", "--title", "Log");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403): POST " + platform.url() + EPICS);
    }

    // --- details ---

    @Test
    void detailsFindsTheEpicByTheStartOfItsIdAndShowsItsFeatureTaskTree() {
        Result r = run("epic", "--project", "qits", "details", "--epic", "AAAA1");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).startsWith("Epic " + TELEMETRY + "\n")
                .containsPattern("slug\\s+live-telemetry\\n")
                .containsPattern("status\\s+REFINING\\n")
                .containsPattern("title\\s+Live telemetry\\n")
                .containsPattern("workspaces\\s+ws-7 on epic/live-telemetry\\n")
                .contains("Description:\n  Stream logs.\n  Red text here\n  Third line\n")
                .contains("Features (1):\n")
                .contains("  stream-logs")
                .contains("Stream logs")
                .contains("not implemented")
                .contains("Tasks (1):\n")
                .contains("wire-the-sse-endpoint")
                .contains("Wire the SSE endpoint")
                .containsPattern("implemented \\d")
                .doesNotContain(String.valueOf(ESC)).doesNotContain("‮").doesNotContain("\r");
        assertThat(platform.requests("GET", EPICS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", "/projects/api/epics/" + TELEMETRY)).hasSize(1);
        assertThat(platform.requests("GET", "/projects/api/epics/" + TELEMETRY + "/features")).hasSize(1);
        assertThat(platform.requests("GET", "/projects/api/features/" + FEATURE + "/tasks")).hasSize(1);
    }

    @Test
    void detailsAsJsonCarriesTheFeatureTaskTree() throws Exception {
        Result r = run("epic", "--project", "qits", "details", "--epic", "aaaa1111", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("epic").path("id").asText()).isEqualTo(TELEMETRY);
        assertThat(printed.path("features")).hasSize(1);
        assertThat(printed.path("features").get(0).path("feature").path("id").asText()).isEqualTo(FEATURE);
        assertThat(printed.path("features").get(0).path("tasks").get(0).path("task").path("id").asText())
                .isEqualTo(TASK);
    }

    @Test
    void anEpicWithNoFeaturesSaysSo() {
        platform.answer("GET", "/projects/api/epics/" + DRILL, """
                {"epic":{"id":"%s","slug":"drill-down","status":"IMPLEMENTATION","title":"Drill down",
                  "description":null}}
                """.formatted(DRILL));
        platform.answer("GET", "/projects/api/epics/" + DRILL + "/features", "{\"entries\":[]}");

        Result r = run("epic", "--project", "qits", "details", "--epic", "aaaa2");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Description:\n  (none)\nFeatures: none.\n").doesNotContain("workspaces");
    }

    @Test
    void anEpicThatFitsNoneSaysWhereToLook() {
        Result r = run("epic", "--project", "qits", "details", "--epic", "9999");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Project qits has no epic with the id or slug '9999', and no epic id starts "
                + "with it. `qits epic --project qits list` shows them.");
        assertThat(platform.requests).noneMatch(req -> req.path().startsWith("/projects/api/epics/"));
    }

    @Test
    void anEpicDeletedAfterTheListIsA404() {
        Result r = run("epic", "--project", "qits", "details", "--epic", "bbbb");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such epic: " + EVIL + " (HTTP 404). It may have been deleted a moment ago.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void detailsNeedsAnEpicAndOnlyDetailsTakesOne() {
        Result none = run("epic", "--project", "qits", "details");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Name the epic: --epic <id, slug or the start of the id>.");

        Result onList = run("epic", "--project", "qits", "--epic", "aaaa1111", "list");
        assertThat(onList.exit()).isEqualTo(2);
        assertThat(onList.err()).contains("--epic is for `qits epic details`, not `qits epic list`.");

        assertThat(platform.requests).isEmpty();
    }

    // --- update ---

    @Test
    void updateSendsTheGivenTitleAndKeepsTheStoredDescription() throws Exception {
        Result r = run("epic", "--project", "qits", "update", "--epic", "AAAA1", "--title", "Live telemetry v2");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("PUT", "/projects/api/epics/" + TELEMETRY).getFirst().body()))
                .isEqualTo(JSON.readTree(
                        "{\"title\":\"Live telemetry v2\",\"description\":\"Stream logs.\\n\\u001b[31mRed\\u001b[0m "
                                + "text\\u202e here\\r\\nThird line\"}"));
        assertThat(r.out()).startsWith("Epic " + TELEMETRY + "\n").containsPattern("title\\s+Live telemetry v2\\n");
    }

    @Test
    void updateSendsTheGivenDescriptionAndKeepsTheStoredTitle() throws Exception {
        Result r = run("epic", "--project", "qits", "update", "--epic", "AAAA1", "--description", "Stream logs.");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("PUT", "/projects/api/epics/" + TELEMETRY).getFirst().body());
        assertThat(sent.path("title").asText()).isEqualTo("Live telemetry");
        assertThat(sent.path("description").asText()).isEqualTo("Stream logs.");
    }

    @Test
    void updateNeedsAtLeastOneField() {
        Result r = run("epic", "--project", "qits", "update", "--epic", "AAAA1");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Give --title, --description or --description-file: there is nothing to change.");
        assertThat(platform.requests("PUT", "/projects/api/epics/" + TELEMETRY)).isEmpty();
    }

    @Test
    void updateRefusesAFrozenScopePlainly() {
        platform.answer("PUT", "/projects/api/epics/" + TELEMETRY, 409,
                "{\"message\":\"The scope of epic " + TELEMETRY + " is frozen: it is IMPLEMENTATION\"}");

        Result r = run("epic", "--project", "qits", "update", "--epic", "AAAA1", "--title", "New title");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The scope of epic " + TELEMETRY + " is frozen: it is IMPLEMENTATION");
    }

    // --- refusals and the project ---

    @Test
    void refusalsCarryTheirStatus() {
        platform.answer("GET", EPICS, 401, "",
                "WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"the token has expired\"");
        Result unauthorized = run("epic", "--project", "qits", "list");
        assertThat(unauthorized.exit()).isEqualTo(1);
        assertThat(unauthorized.err()).contains("The platform refused the token (HTTP 401: invalid_token, the token "
                + "has expired).");

        platform.answer("GET", EPICS, 403, "");
        Result forbidden = run("epic", "--project", "qits", "details", "--epic", "aaaa1");
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains("Your roles do not allow this (HTTP 403): GET " + platform.url() + EPICS);
    }

    @Test
    void anUnknownProjectNamesTheOnesThereAre() {
        Result r = run("epic", "--project", "nope", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("No project has the id, slug or name 'nope'. Projects: qits.");
        assertThat(platform.requests("GET", EPICS)).isEmpty();
    }
}
