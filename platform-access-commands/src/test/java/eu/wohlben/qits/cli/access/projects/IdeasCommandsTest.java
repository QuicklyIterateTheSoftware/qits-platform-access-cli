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
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * qits ideas list|details|create|update|archive|unarchive|spawn (epic qits-888), as a person types
 * them, in this process, against a fake platform and a fake idp.
 */
class IdeasCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char ESC = 0x1B;

    private static final String PROJECT = "11111111-0000-4000-8000-000000000001";
    private static final String IDEA = "22222222-0000-4000-8000-000000000002";
    private static final String ENABLER = "33333333-0000-4000-8000-000000000003";
    private static final String ENABLED = "44444444-0000-4000-8000-000000000004";
    private static final String IDEAS = "/projects/api/ideas";
    private static final String WORK = "/projects/api/work";
    private static final String OPENAPI = "/projects/q/openapi";

    /** An idea as the list and the writes answer it. Its title carries an escape sequence, as anybody can write one. */
    private static final String IDEA_ROW = """
            {"id":"%s","projectId":"%s","title":"Faster \\u001b[2Jbuilds","description":"Cache the dependencies.",
             "enabledBy":"%s","status":"QUEUED","archived":false,"archiveReason":null,"archivedAt":null,
             "createdAt":"2026-10-30T08:00:00Z","updatedAt":"2026-10-30T09:00:00Z"}
            """.formatted(IDEA, PROJECT, ENABLER);

    private static final String ARCHIVED_ROW = """
            {"id":"%s","projectId":"%s","title":"Nightly rebuilds","description":null,"enabledBy":null,
             "status":"PLANNED","archived":true,"archiveReason":"Not worth it.","archivedAt":"2026-10-30T09:30:00Z",
             "createdAt":"2026-10-30T08:00:00Z","updatedAt":"2026-10-30T09:30:00Z"}
            """.formatted(ENABLER, PROJECT);

    /** The idea in detail: its enabler is archived, it enables one idea and inspired one epic. */
    private static final String IDEA_DETAIL = """
            {"id":"%s","projectId":"%s","title":"Faster builds","description":"Cache the dependencies.\\n\\nShare it.",
             "enabledBy":"%s","enabledByIdea":{"id":"%s","title":"Nightly rebuilds","status":"PLANNED","archived":true},
             "status":"QUEUED","archived":false,"archiveReason":null,"archivedAt":null,
             "createdAt":"2026-10-30T08:00:00Z","updatedAt":"2026-10-30T09:00:00Z",
             "inspired":[{"id":"55555555-0000-4000-8000-000000000005","qualifiedId":"qits-900",
               "title":"Cache the build dependencies","status":"REPORTED","archetype":"EPIC"}],
             "enables":[{"id":"%s","title":"Share the build cache","status":"PLANNED","archived":false}]}
            """.formatted(IDEA, PROJECT, ENABLER, ENABLER, ENABLED);

    private static final String EPIC_ROW = """
            {"id":"66666666-0000-4000-8000-000000000006","archetype":"EPIC","projectId":"%s","qualifiedId":"qits-901",
             "title":"Faster builds","status":"REPORTED","inspiredBy":"%s",
             "createdAt":"2026-10-30T10:00:00Z","updatedAt":"2026-10-30T10:00:00Z"}
            """.formatted(PROJECT, IDEA);

    private static final String EPIC_CREATE = """
            {"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},
             "project":{"type":"string"},"inspiredBy":{"type":"string"}},
             "required":["title","project"],"additionalProperties":false}
            """;

    private static final String TICKET_CREATE = """
            {"type":"object","properties":{"title":{"type":"string"},"description":{"type":"string"},
             "ticketType":{"type":"string"},"impetus":{"type":"string"},"project":{"type":"string"},
             "inspiredBy":{"type":"string"}},
             "required":["title","ticketType","impetus","project"],"additionalProperties":false}
            """;

    private static final String DOCUMENT = """
            {"openapi":"3.1.0","paths":{
              "/projects/api/ideas":{"post":{"requestBody":{"content":{"application/json":{
                "schema":{"$ref":"#/components/schemas/IdeaCreate"}}}}}},
              "/projects/api/ideas/{id}":{"patch":{"requestBody":{"content":{
                "application/merge-patch+json":{"schema":{"$ref":"#/components/schemas/IdeaPatch"}},
                "application/json":{"schema":{"$ref":"#/components/schemas/IdeaPatch"}}}}}}},
             "components":{"schemas":{
              "IdeaCreate":{"type":"object","required":["title"],"properties":{"project":{"type":"string"},
                "title":{"type":"string"},"description":{"type":"string"},"enabledBy":{"type":"string"}}},
              "IdeaPatch":{"type":"object","description":"A JSON merge patch of one idea.",
                "properties":{"title":{"type":"string"},"description":{"type":["string","null"]},
                "enabledBy":{"type":["string","null"]}}}}}}
            """;

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

        platform.answer("GET", IDEAS, "{\"ideas\":[" + IDEA_ROW + "," + ARCHIVED_ROW + "]}");
        platform.answer("GET", IDEAS + "/" + IDEA, IDEA_DETAIL);
        platform.answer("POST", IDEAS, 201, IDEA_ROW);
        platform.answer("PATCH", IDEAS + "/" + IDEA, IDEA_ROW);
        platform.answer("POST", IDEAS + "/" + IDEA + "/archive", ARCHIVED_ROW.replace(ENABLER, IDEA));
        platform.answer("POST", IDEAS + "/" + ENABLER + "/unarchive", IDEA_ROW.replace(IDEA, ENABLER));
        platform.answer("GET", OPENAPI, DOCUMENT);
        platform.answer("GET", WORK + "/archetypes/EPIC/schemas/create", EPIC_CREATE);
        platform.answer("GET", WORK + "/archetypes/TICKET/schemas/create", TICKET_CREATE);
        platform.answer("POST", WORK, 201, EPIC_ROW);
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

    private Result runWithInput(String stdin, String... args) {
        return runWithInput(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)), args);
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

    private boolean anyWrite() {
        synchronized (platform.requests) {
            return platform.requests.stream().anyMatch(r -> !r.method().equals("GET"));
        }
    }

    private JsonNode sent(String method, String path) throws Exception {
        List<FakePlatform.Request> all = platform.requests(method, path);
        assertThat(all).as(method + " " + path).hasSize(1);
        return JSON.readTree(all.getFirst().body());
    }

    // --- list ---

    @Test
    void listPrintsATableAndSendsTheProjectAlone() {
        Result r = run("ideas", "list", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", IDEAS).getFirst().query()).isEqualTo("project=qits");
        assertThat(r.out().lines().toList().getFirst()).startsWith("ID").contains("STATUS").contains("ARCHIVED")
                .contains("TITLE");
        assertThat(r.out()).contains(IDEA).contains("QUEUED").contains("Faster").contains("Nightly rebuilds")
                .doesNotContain(String.valueOf(ESC));
    }

    @Test
    void listAsksForTheArchivedOnesAndAStatusInCapitals() {
        Result r = run("ideas", "list", "--project", "qits", "--archived", "--status", "planned");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", IDEAS).getFirst().query())
                .isEqualTo("project=qits&archived=true&status=PLANNED");
    }

    @Test
    void listWithoutAProjectIsAUsageErrorWithNoRequest() {
        Result r = run("ideas", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--project");
        assertThat(platform.requests).isEmpty();
    }

    // --- details ---

    @Test
    void detailsShowsTheIdeaAHintForAnArchivedEnablerWhatItEnablesAndTheWorkItInspired() {
        Result r = run("ideas", "--entity", IDEA, "details");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("IDEA " + IDEA)
                .contains("QUEUED")
                .contains("enabled by").contains(ENABLER + " (Nightly rebuilds)")
                .contains("hint: enabled by an archived idea " + ENABLER)
                .contains("Description:").contains("Share it.")
                .contains("Enables (1)").contains(ENABLED).contains("Share the build cache")
                .contains("Inspired (1)").contains("qits-900").contains("EPIC").contains("REPORTED")
                .contains("Cache the build dependencies");
    }

    @Test
    void detailsGivesNoHintWhenTheEnablerIsNotArchived() {
        platform.answer("GET", IDEAS + "/" + IDEA, IDEA_DETAIL.replace("\"archived\":true", "\"archived\":false"));

        Result r = run("ideas", "details", "--entity", IDEA);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("enabled by").doesNotContain("hint:");
    }

    @Test
    void detailsOfAnArchivedIdeaSaysWhy() {
        platform.answer("GET", IDEAS + "/" + ENABLER, ARCHIVED_ROW.replace("}", ",\"inspired\":[],\"enables\":[]}"));

        Result r = run("ideas", "details", "--entity", ENABLER);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("yes: Not worth it.").contains("Description:").contains("(none)")
                .contains("Enables: none.").contains("Inspired: none.");
    }

    @Test
    void detailsAsJsonIsTheServicesAnswer() throws Exception {
        Result r = run("ideas", "details", "--entity", IDEA, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(IDEA_DETAIL));
    }

    @Test
    void detailsWithoutAnEntityIsAUsageError() {
        Result r = run("ideas", "details");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--entity");
        assertThat(platform.requests).isEmpty();
    }

    // --- create and update ---

    @Test
    void createSendsThePayloadUnchangedAndPrintsTheIdea() throws Exception {
        String payload = "{\"project\":\"qits\",\"title\":\"Faster builds\",\"extra\":[1]}";
        Result r = runWithInput(payload, "ideas", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", IDEAS)).isEqualTo(JSON.readTree(payload));
        assertThat(platform.requests("GET", OPENAPI)).isEmpty();
        assertThat(r.out()).contains(IDEA).contains("QUEUED").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void createWithNothingOnStdinPrintsTheSchemaFromTheOpenApiDocumentAndSendsNothing() {
        Result r = run("ideas", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out()).contains("Nothing on stdin, so nothing was sent.")
                .contains("Required: title.")
                .contains("POST /projects/api/ideas")
                .contains("\"enabledBy\"");
    }

    @Test
    void updatePatchesTheIdeaAsAMergePatch() throws Exception {
        String patch = "{\"title\":\"new\",\"enabledBy\":null}";
        Result r = runWithInput(patch, "ideas", "--entity", IDEA, "update");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("PATCH", IDEAS + "/" + IDEA).getFirst();
        assertThat(sent.contentType()).isEqualTo("application/merge-patch+json");
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(patch));
    }

    @Test
    void updateWithNothingOnStdinPrintsThePatchSchema() {
        Result r = run("ideas", "update");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out()).contains("PATCH /projects/api/ideas/{id}").contains("application/merge-patch+json")
                .contains("A JSON merge patch of one idea.");
    }

    @Test
    void updateWithoutAnEntityIsAUsageErrorWithNoRequest() {
        Result r = runWithInput("{\"title\":\"x\"}", "ideas", "update");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--entity");
        assertThat(platform.requests).isEmpty();
    }

    // --- archive and unarchive ---

    @Test
    void archiveSendsTheReason() throws Exception {
        Result r = run("ideas", "--entity", IDEA, "archive", "--reason", "Folded into the cache epic.");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", IDEAS + "/" + IDEA + "/archive"))
                .isEqualTo(JSON.readTree("{\"reason\":\"Folded into the cache epic.\"}"));
        assertThat(r.out()).contains(IDEA).contains("yes");
    }

    @Test
    void archiveWithoutAReasonIsAUsageErrorWithNoRequest() {
        Result none = run("ideas", "--entity", IDEA, "archive");
        Result blank = run("ideas", "--entity", IDEA, "archive", "--reason", "  ");

        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("--reason");
        assertThat(blank.exit()).isEqualTo(2);
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void unarchiveSendsNoBodyAndPrintsTheIdea() {
        Result r = run("ideas", "--entity", ENABLER, "unarchive", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", IDEAS + "/" + ENABLER + "/unarchive")).hasSize(1);
        assertThat(r.out()).contains("\"archived\" : false").doesNotContain(String.valueOf(ESC));
    }

    // --- spawn ---

    @Test
    void spawnFilesAnEpicFromTheIdeaThroughTheWorkDoor() throws Exception {
        Result r = runWithInput("{}", "ideas", "--entity", IDEA, "spawn", "--archetype", "epic");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK)).isEqualTo(JSON.readTree("""
                {"project":"%s","title":"Faster builds","description":"Cache the dependencies.\\n\\nShare it.",
                 "inspiredBy":"%s","archetype":"EPIC"}
                """.formatted(PROJECT, IDEA)));
        assertThat(platform.requests("GET", WORK + "/archetypes/EPIC/schemas/create")).isEmpty();
        assertThat(r.out().lines().toList().getFirst()).startsWith("ID").contains("ARCHETYPE");
        assertThat(r.out()).contains("qits-901");
    }

    @Test
    void spawnMergesThePayloadOverWhatTheIdeaFillsIn() throws Exception {
        Result r = runWithInput("{\"ticketType\":\"IMPROVEMENT\",\"impetus\":\"Slow.\",\"description\":null,"
                + "\"title\":\"Cache it\"}", "ideas", "spawn", "--entity", IDEA, "--archetype", "TICKET");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK)).isEqualTo(JSON.readTree("""
                {"project":"%s","title":"Cache it","inspiredBy":"%s","ticketType":"IMPROVEMENT","impetus":"Slow.",
                 "archetype":"TICKET"}
                """.formatted(PROJECT, IDEA)));
    }

    @Test
    void spawnWithNothingOnStdinPrintsWhatTheIdeaFillsInAndWhatIsMissing() {
        Result r = run("ideas", "--entity", IDEA, "spawn", "--archetype", "ticket");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out()).contains("Usage: qits ideas spawn")
                .contains("Filled in from the idea: project, title, description, inspiredBy.")
                .contains("Required and missing (put them on stdin): ticketType, impetus.")
                .contains("From the service: GET /projects/api/work/archetypes/TICKET/schemas/create");
    }

    @Test
    void spawnRefusesAnotherArchetypeAndAConflictingOneWithNoRequest() {
        Result feature = runWithInput("{}", "ideas", "--entity", IDEA, "spawn", "--archetype", "feature");
        Result conflict = runWithInput("{\"archetype\":\"TICKET\"}", "ideas", "--entity", IDEA, "spawn",
                "--archetype", "epic");
        Result none = runWithInput("{}", "ideas", "--entity", IDEA, "spawn");

        assertThat(feature.exit()).isEqualTo(2);
        assertThat(feature.err()).contains("EPIC or TICKET").contains("Nothing was sent.");
        assertThat(conflict.exit()).isEqualTo(2);
        assertThat(conflict.err()).contains("Nothing was sent.");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("--archetype");
        assertThat(platform.requests).isEmpty();
    }

    // --- qits work, the other side of inspiredBy ---

    @Test
    void workDetailsShowsWhatInspiredTheItem() {
        platform.answer("GET", WORK + "/qits-901", EPIC_ROW);
        platform.answer("GET", WORK + "/qits-901/comments", "{\"entries\":[]}");
        platform.answer("GET", WORK + "/qits-901/children", "{\"children\":[]}");
        platform.answer("GET", "/projects/api/projects/" + PROJECT + "/work", "{\"entities\":[]}");

        Result r = run("work", "--entity", "qits-901", "details");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("inspired by").contains(IDEA);
    }

    @Test
    void workCreateSendsInspiredByUnchanged() throws Exception {
        String payload = "{\"project\":\"qits\",\"title\":\"Faster builds\",\"inspiredBy\":\"" + IDEA + "\"}";
        Result r = runWithInput(payload, "work", "create", "--archetype", "epic");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK).path("inspiredBy").asText()).isEqualTo(IDEA);
    }
}
