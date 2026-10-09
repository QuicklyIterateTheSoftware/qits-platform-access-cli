package eu.wohlben.qits.cli.access.projects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * qits work list|details|create|update|transition|status, as a person types them, in this process,
 * against a fake platform and a fake idp. Every schema comes from a canned answer of the service's
 * schema door: the commands keep no copy of their own.
 */
class WorkCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-29T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char ESC = 0x1B;

    private static final String PROJECT = "11111111-0000-4000-8000-000000000001";
    private static final String TICKET = "45a14f8e-f550-45bb-a117-6b34d8c472e3";
    private static final String EPIC = "6f0c2d1e-0000-4000-8000-000000000002";
    private static final String FEATURE = "7a7a7a7a-0000-4000-8000-000000000003";
    private static final String TASK = "8b8b8b8b-0000-4000-8000-000000000004";
    private static final String WORK = "/projects/api/work";
    private static final String LIST = "/projects/api/projects/qits/work";
    private static final String REGISTRY = "/projects/api/work/archetypes";
    private static final String SCHEMAS = "/projects/api/work/archetypes/";

    /** A ticket as GET /work/{id} answers it. Its title carries an escape sequence, as anybody can write one. */
    private static final String TICKET_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":548,"qualifiedId":"qits-548",
             "title":"One group \\u001b[2Jfor work","slug":"one-group","description":"The brief.","status":"REPORTED",
             "ticketType":"IMPROVEMENT","impetus":"The CLI drifted.","assignee":"alice","createdBy":"bob",
             "supersededBy":null,"repositoryId":null,"implementedAt":null,"dependsOn":null,"parent":null,
             "position":null,"createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z","blocked":true}
            """.formatted(TICKET, PROJECT);

    private static final String FEATURE_ROW = """
            {"id":"%s","archetype":"FEATURE","projectId":"%s","number":601,"qualifiedId":"qits-601",
             "title":"Retry","description":"d","status":"REFINED","dependsOn":null,"parent":"%s","position":2,
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z"}
            """.formatted(FEATURE, PROJECT, EPIC);

    /** A TASK as an old, not-yet-migrated service still answers it: no status, no lifecycle. */
    private static final String STALE_TASK_ROW = """
            {"id":"%s","archetype":"TASK","projectId":"%s","number":602,"qualifiedId":"qits-602",
             "title":"Old task","status":null,"parent":"%s","position":1,
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z"}
            """.formatted(TASK, PROJECT, FEATURE);

    private static final String EPIC_ROW = """
            {"id":"%s","archetype":"EPIC","projectId":"%s","number":120,"qualifiedId":"qits-120",
             "title":"The epic","status":"REFINED","parent":null,"position":null,
             "acceptanceCriteria":["Loads under 200ms","Shows an empty state when there is nothing"],
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z"}
            """.formatted(EPIC, PROJECT);

    // --- blockSource (qits-895): a ticket blocked by an idle agent session, an explicit block with and
    // without its reason and who, both at once, and one sent not blocked at all. ---

    private static final String AGENT_WAITING_TICKET = "90909090-0000-4000-8000-000000000005";
    private static final String AGENT_WAITING_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":700,"qualifiedId":"qits-700",
             "title":"Idle agent","description":"d","status":"IMPLEMENTING","createdBy":"bob",
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z",
             "blocked":true,"blockSource":"AGENT_WAITING"}
            """.formatted(AGENT_WAITING_TICKET, PROJECT);

    private static final String EXPLICIT_BLOCK_TICKET = "a1a1a1a1-0000-4000-8000-000000000006";
    private static final String EXPLICIT_BLOCK_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":701,"qualifiedId":"qits-701",
             "title":"Needs sign-off","description":"d","status":"REFINED","createdBy":"bob",
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z",
             "blocked":true,"blockSource":"EXPLICIT","blockReason":"Needs security sign-off","blockedBy":"alice"}
            """.formatted(EXPLICIT_BLOCK_TICKET, PROJECT);

    /** The same explicit block, with neither a reason nor who recorded. */
    private static final String EXPLICIT_BLOCK_BARE_TICKET = "a2a2a2a2-0000-4000-8000-000000000007";
    private static final String EXPLICIT_BLOCK_BARE_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":702,"qualifiedId":"qits-702",
             "title":"Blocked, no reason given","description":"d","status":"REFINED","createdBy":"bob",
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z",
             "blocked":true,"blockSource":"EXPLICIT"}
            """.formatted(EXPLICIT_BLOCK_BARE_TICKET, PROJECT);

    private static final String BOTH_BLOCK_TICKET = "b0b0b0b0-0000-4000-8000-000000000008";
    private static final String BOTH_BLOCK_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":703,"qualifiedId":"qits-703",
             "title":"Blocked and idle","description":"d","status":"REFINED","createdBy":"bob",
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z",
             "blocked":true,"blockSource":"BOTH","blockReason":"Needs security sign-off","blockedBy":"alice"}
            """.formatted(BOTH_BLOCK_TICKET, PROJECT);

    private static final String NOT_BLOCKED_TICKET = "c0c0c0c0-0000-4000-8000-000000000009";
    private static final String NOT_BLOCKED_ROW = """
            {"id":"%s","archetype":"TICKET","projectId":"%s","number":704,"qualifiedId":"qits-704",
             "title":"Not blocked","description":"d","status":"REFINED","createdBy":"bob",
             "createdAt":"2026-09-29T08:00:00Z","updatedAt":"2026-09-29T09:00:00Z","blocked":false}
            """.formatted(NOT_BLOCKED_TICKET, PROJECT);

    private static final String TICKET_CREATE = """
            {"title":"TICKET create","type":"object","additionalProperties":false,
             "required":["title","ticketType","impetus","project"],
             "properties":{"title":{"type":"string","pattern":"\\\\S"},"description":{"type":"string"},
               "ticketType":{"type":"string","enum":["BUG","IMPROVEMENT"]},"impetus":{"type":"string"},
               "assignee":{"type":"string"},"project":{"type":"string"}}}
            """;

    private static final String TICKET_UPDATE = """
            {"title":"TICKET update","type":"object","additionalProperties":false,"required":[],
             "description":"absent = unchanged, null = clear",
             "properties":{"title":{"type":"string"},"description":{"type":["string","null"]},
               "ticketType":{"type":"string"},"impetus":{"type":["string","null"]},"assignee":{"type":["string","null"]}}}
            """;

    private static final String EPIC_TRANSITION = """
            {"title":"EPIC transition","type":"object","additionalProperties":false,"required":["title","status"],
             "properties":{"title":{"type":"string"},"description":{"type":"string"},"status":{"type":"string"},
               "supersededBy":{"type":"string"},"implementedAt":{"type":"string","format":"date-time"},
               "acceptanceCriteria":{"type":"array","items":{"type":"string"}},
               "membership":{"type":"object","required":["parent"],
                 "properties":{"parent":{"type":"string"},"position":{"type":"integer"}}}}}
            """;

    private static final String FEATURE_TRANSITION = """
            {"title":"FEATURE transition","type":"object","additionalProperties":false,"required":["title","membership"],
             "properties":{"title":{"type":"string"},"description":{"type":"string"},"dependsOn":{"type":"string"},
               "membership":{"type":"object","required":["parent"],
                 "properties":{"parent":{"type":"string"},"position":{"type":"integer"}}}}}
            """;

    private static final String ARCHETYPES = """
            {"archetypes":[
              {"archetype":"EPIC","lifecycle":["REPORTED","REFINED","IMPLEMENTED","VERIFIED","DONE"],
               "transitions":{"REFINED":[{"to":"IMPLEMENTED","kind":"FORWARD"},{"to":"DROPPED","kind":"EXIT"}]}},
              {"archetype":"TICKET","lifecycle":["REPORTED","REFINED","IMPLEMENTED","VERIFIED","DONE"],
               "transitions":{"REPORTED":[{"to":"REFINED","kind":"FORWARD"},{"to":"DROPPED","kind":"EXIT"}],
                              "REFINED":[{"to":"IMPLEMENTED","kind":"FORWARD"}]}},
              {"archetype":"FEATURE","lifecycle":["REPORTED","REFINED","IMPLEMENTED","VERIFIED","DONE"],
               "transitions":{"REFINED":[{"to":"IMPLEMENTED","kind":"FORWARD"},{"to":"DROPPED","kind":"EXIT"}]}}]}
            """;

    /** The registry an old, not-yet-migrated service serves: TASK still carries an empty lifecycle. */
    private static final String ARCHETYPES_STALE_TASK = """
            {"archetypes":[
              {"archetype":"EPIC","lifecycle":["REPORTED","REFINED","IMPLEMENTED","VERIFIED","DONE"],
               "transitions":{"REFINED":[{"to":"IMPLEMENTED","kind":"FORWARD"},{"to":"DROPPED","kind":"EXIT"}]}},
              {"archetype":"TASK","lifecycle":[],"transitions":{}}]}
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

        answers();
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

    private void answers() {
        platform.answer("GET", WORK + "/qits-548", TICKET_ROW);
        platform.answer("GET", WORK + "/" + TICKET, TICKET_ROW);
        platform.answer("GET", WORK + "/qits-601", FEATURE_ROW);
        platform.answer("GET", WORK + "/qits-120", EPIC_ROW);
        platform.answer("GET", REGISTRY, ARCHETYPES);
        platform.answer("GET", SCHEMAS + "TICKET/schemas/create", TICKET_CREATE);
        platform.answer("GET", SCHEMAS + "TICKET/schemas/update", TICKET_UPDATE);
        platform.answer("GET", SCHEMAS + "EPIC/schemas/transition", EPIC_TRANSITION);
        platform.answer("GET", SCHEMAS + "FEATURE/schemas/transition", FEATURE_TRANSITION);
        platform.answer("GET", WORK + "/qits-120/comments", "{\"entries\":[]}");
        platform.answer("GET", WORK + "/qits-120/children", "{\"children\":[]}");
        platform.answer("GET", WORK + "/qits-548/children", "{\"children\":[]}");
        platform.answer("GET", WORK + "/qits-548/comments", """
                {"entries":[{"comment":{"id":"c1","entityId":"%s","author":"carol","body":"Seen \\u001b[31mit",
                  "createdAt":"2026-09-29T09:30:00Z","updatedAt":"2026-09-29T09:30:00Z"}}]}
                """.formatted(TICKET));
        platform.answer("GET", LIST, "{\"entities\":[" + TICKET_ROW + "," + EPIC_ROW + "]}");
        platform.answer("POST", WORK, 201, TICKET_ROW.replace("qits-548", "qits-600"));
        platform.answer("PATCH", WORK + "/qits-548", TICKET_ROW);
        platform.answer("POST", WORK + "/transition", "{\"qits-548\":"
                + TICKET_ROW.replace("\"archetype\":\"TICKET\"", "\"archetype\":\"EPIC\"") + "}");
        platform.answer("POST", WORK + "/qits-548/status",
                TICKET_ROW.replace("\"status\":\"REPORTED\"", "\"status\":\"REFINED\",\"statusBefore\":\"REPORTED\""));
    }

    private JsonNode sent(String method, String path) throws Exception {
        List<FakePlatform.Request> all = platform.requests(method, path);
        assertThat(all).as(method + " " + path).hasSize(1);
        return JSON.readTree(all.getFirst().body());
    }

    // --- create ---

    @Test
    void createSendsThePayloadWithTheArchetypeSetAndPrintsTheQualifiedId() throws Exception {
        String payload = "{\"project\":\"qits\",\"title\":\"x\",\"ticketType\":\"IMPROVEMENT\",\"impetus\":\"y\"}";
        Result r = runWithInput(payload, "work", "create", "--archetype", "ticket");

        assertThat(r.exit()).as(r.err()).isZero();
        ObjectNode expected = (ObjectNode) JSON.readTree(payload);
        expected.put("archetype", "TICKET");
        assertThat(sent("POST", WORK)).isEqualTo(expected);
        // A payload needs no schema.
        assertThat(platform.requests("GET", SCHEMAS + "TICKET/schemas/create")).isEmpty();
        assertThat(r.out().lines().toList().getFirst()).startsWith("ID").contains("ARCHETYPE").contains("STATUS");
        assertThat(r.out()).contains("qits-600").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void createWithNothingOnStdinPrintsUsageAndTheServedSchemaAndSendsNothing() {
        Result r = run("work", "create", "--archetype", "ticket");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(anyWrite()).isFalse();
        assertThat(r.out())
                .contains("Usage: qits work create")
                .contains("Nothing on stdin, so nothing was sent.")
                .contains("Required: title, ticketType, impetus, project.")
                .contains("From the service: GET /projects/api/work/archetypes/TICKET/schemas/create")
                .contains("\"additionalProperties\" : false");
        assertThat(r.out().indexOf("Usage:")).isLessThan(r.out().indexOf("Required:"));
    }

    @Test
    void theSchemaAsJsonIsTheServedSchemaAlone() throws Exception {
        Result r = run("work", "create", "--archetype", "TICKET", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(TICKET_CREATE));
    }

    @Test
    void createWithoutAnArchetypeOrWithAConflictingOneIsAUsageErrorWithNoRequest() {
        Result none = runWithInput("{\"title\":\"x\"}", "work", "create");
        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("--archetype");

        Result conflict = runWithInput("{\"archetype\":\"EPIC\"}", "work", "create", "--archetype", "ticket");
        assertThat(conflict.exit()).isEqualTo(2);
        assertThat(conflict.err()).contains("Nothing was sent.");

        assertThat(platform.requests).isEmpty();
    }

    @Test
    void anUnknownArchetypeIsTheServicesAnswer() {
        platform.answer("GET", SCHEMAS + "WIDGET/schemas/create", 404, "{\"message\":\"no archetype WIDGET\"}");

        Result r = run("work", "create", "--archetype", "widget");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Nothing was sent.").contains("HTTP 404").contains("no archetype WIDGET");
    }

    // --- every write: bad payloads and empty stdin ---

    @Test
    void aPayloadThatIsNotAJsonObjectIsAUsageErrorWithNoRequestOnEveryWrite() {
        List<String[]> writes = List.of(
                new String[] {"work", "create", "--archetype", "ticket"},
                new String[] {"work", "--entity", "qits-548", "update"},
                new String[] {"work", "--entity", "qits-548", "transition", "--archetype", "epic"},
                new String[] {"work", "--entity", "qits-548", "status"});
        for (String[] write : writes) {
            Result broken = runWithInput("{\"title\": \"unterminated", write);
            assertThat(broken.exit()).as(String.join(" ", write)).isEqualTo(2);
            assertThat(broken.err()).contains("not JSON").contains("Nothing was sent.");
            Result array = runWithInput("[1]", write);
            assertThat(array.exit()).isEqualTo(2);
            assertThat(array.err()).contains("must be a JSON object, not an array");
        }
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void emptyStdinSendsNoWriteOnEveryWrite() {
        List<String[]> writes = List.of(
                new String[] {"work", "create", "--archetype", "ticket"},
                new String[] {"work", "--entity", "qits-548", "update"},
                new String[] {"work", "--entity", "qits-548", "transition", "--archetype", "epic"},
                new String[] {"work", "--entity", "qits-548", "status"});
        for (String[] write : writes) {
            Result r = runWithInput(" \n", write);
            assertThat(r.exit()).as(String.join(" ", write) + ": " + r.err()).isZero();
            assertThat(r.out()).contains("Usage: qits work").contains("nothing was sent");
        }
        assertThat(anyWrite()).isFalse();
    }

    // --- update ---

    @Test
    void updatePatchesTheQualifiedIdAsGivenWithThePayloadUnchangedAndLooksNothingUp() throws Exception {
        String patch = "{\"title\":\"new\",\"assignee\":null,\"extra\":{\"kept\":[1]}}";
        Result r = runWithInput(patch, "work", "--entity", "qits-548", "update");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request sent = platform.requests("PATCH", WORK + "/qits-548").getFirst();
        assertThat(sent.contentType()).isEqualTo("application/merge-patch+json");
        assertThat(JSON.readTree(sent.body())).isEqualTo(JSON.readTree(patch));
        // No client-side resolution: the service takes the qualified id itself.
        assertThat(platform.requests("GET", WORK + "/qits-548")).isEmpty();
        assertThat(platform.requests("PATCH", WORK + "/" + TICKET)).isEmpty();
        assertThat(r.out()).contains("qits-548");
    }

    @Test
    void updatePassesAUuidThroughAsItIs() throws Exception {
        platform.answer("PATCH", WORK + "/" + TICKET, TICKET_ROW);

        Result r = runWithInput("{\"title\":\"new\"}", "work", "--entity", TICKET, "update");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("PATCH", WORK + "/" + TICKET)).hasSize(1);
    }

    @Test
    void updateWithNothingOnStdinPrintsTheUpdateSchemaOfTheItemsArchetype() {
        Result r = run("work", "--entity", "qits-548", "update");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Usage: qits work update")
                .contains("Required: nothing.")
                .contains("From the service: GET /projects/api/work/archetypes/TICKET/schemas/update")
                .contains("absent = unchanged, null = clear");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void updateWithoutAnEntityIsAUsageErrorWithNoRequest() {
        Result r = runWithInput("{\"title\":\"x\"}", "work", "update");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--entity");
        assertThat(platform.requests).isEmpty();
    }

    // --- transition ---

    @Test
    void transitionMergesOverTheCurrentRowDropsWhatHasNoSlotAndNamesIt() throws Exception {
        Result r = runWithInput("{\"title\":\"Now an epic\",\"description\":null,\"impetus\":\"kept?\"}",
                "work", "--entity", "qits-548", "transition", "--archetype", "epic");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode body = sent("POST", WORK + "/transition");
        // Keyed by the entity as it was given, never resolved to a UUID; the full state, with archetype set.
        assertThat(body.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("qits-548");
        assertThat(body.get("qits-548")).isEqualTo(JSON.readTree(
                "{\"title\":\"Now an epic\",\"status\":\"REPORTED\",\"archetype\":\"EPIC\"}"));
        assertThat(r.err()).contains("ticketType, impetus, assignee have no slot on EPIC and are not carried.");
        assertThat(r.out()).contains("qits-548").contains("EPIC");
    }

    @Test
    void transitionCarriesMembershipAndANullClearsIt() throws Exception {
        Result kept = runWithInput("{}", "work", "--entity", "qits-601", "transition", "--archetype", "feature");
        assertThat(kept.exit()).as(kept.err()).isZero();
        assertThat(sent("POST", WORK + "/transition").get("qits-601")).isEqualTo(JSON.readTree(
                "{\"title\":\"Retry\",\"description\":\"d\",\"membership\":{\"parent\":\"" + EPIC
                        + "\",\"position\":2},\"archetype\":\"FEATURE\"}"));
        platform.requests.clear();

        Result root = runWithInput("{\"membership\":null,\"status\":\"REPORTED\"}",
                "work", "--entity", "qits-601", "transition", "--archetype", "epic");
        assertThat(root.exit()).as(root.err()).isZero();
        assertThat(sent("POST", WORK + "/transition").get("qits-601")).isEqualTo(JSON.readTree(
                "{\"title\":\"Retry\",\"description\":\"d\",\"status\":\"REPORTED\",\"archetype\":\"EPIC\"}"));
        assertThat(root.err()).doesNotContain("no slot");
    }

    @Test
    void transitionCarriesAcceptanceCriteriaWhenTheTargetHasASlotAndDropsItOtherwise() throws Exception {
        Result kept = runWithInput("{}", "work", "--entity", "qits-120", "transition", "--archetype", "epic");
        assertThat(kept.exit()).as(kept.err()).isZero();
        assertThat(sent("POST", WORK + "/transition").get("qits-120")).isEqualTo(JSON.readTree(
                "{\"title\":\"The epic\",\"status\":\"REFINED\","
                        + "\"acceptanceCriteria\":[\"Loads under 200ms\",\"Shows an empty state when there is nothing\"],"
                        + "\"archetype\":\"EPIC\"}"));
        assertThat(kept.err()).doesNotContain("no slot");
        platform.requests.clear();

        Result dropped = runWithInput("{\"membership\":{\"parent\":\"qits-7\"}}",
                "work", "--entity", "qits-120", "transition", "--archetype", "feature");
        assertThat(dropped.exit()).as(dropped.err()).isZero();
        assertThat(dropped.err()).contains("status, acceptanceCriteria have no slot on FEATURE and are not carried.");
        JsonNode featureState = sent("POST", WORK + "/transition").get("qits-120");
        // A qualified id in membership.parent goes through as it was written.
        assertThat(featureState.path("membership").path("parent").asText()).isEqualTo("qits-7");
        assertThat(featureState.has("acceptanceCriteria")).isFalse();
        assertThat(featureState.has("status")).isFalse();
    }

    @Test
    void transitionWithNothingOnStdinMarksTheRequiredPropertiesTheItemLacks() {
        Result toFeature = run("work", "--entity", "qits-548", "transition", "--archetype", "feature");
        assertThat(toFeature.exit()).as(toFeature.err()).isZero();
        assertThat(toFeature.out()).contains("Usage: qits work transition")
                .contains("Required: title, membership.")
                .contains("Required and missing (the item does not carry them yet): membership.parent.")
                .contains("Not carried: status, ticketType, impetus, assignee have no slot on FEATURE.")
                .contains("From the service: GET /projects/api/work/archetypes/FEATURE/schemas/transition");

        Result toEpic = run("work", "--entity", "qits-548", "transition", "--archetype", "epic");
        assertThat(toEpic.out()).contains("Required and missing (the item does not carry them yet): nothing.")
                .contains("Not carried: ticketType, impetus, assignee have no slot on EPIC.");

        Result featureToEpic = run("work", "--entity", "qits-601", "transition", "--archetype", "epic");
        assertThat(featureToEpic.out()).contains("Required and missing (the item does not carry them yet): nothing.");

        assertThat(anyWrite()).isFalse();
    }

    @Test
    void aConflictOnTransitionIsTheServicesAnswer() {
        platform.answer("POST", WORK + "/transition", 409, "{\"message\":\"the row moved under you\"}");

        Result r = runWithInput("{}", "work", "--entity", "qits-548", "transition", "--archetype", "epic");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 409").contains("the row moved under you");
    }

    // --- status ---

    @Test
    void statusWithNothingOnStdinPrintsTheMovesTheRegistryOpensFromTheCurrentStatus() throws Exception {
        Result table = run("work", "--entity", "qits-548", "status");
        assertThat(table.exit()).as(table.err()).isZero();
        assertThat(table.out()).contains("Usage: qits work status")
                .contains("Required: target.")
                .contains("Current status: REPORTED. Open moves: REFINED, DROPPED.")
                .contains("From the service: GET /projects/api/work/archetypes");

        Result json = run("work", "--entity", "qits-548", "status", "-o", "json");
        assertThat(JSON.readTree(json.out()).path("properties").path("target"))
                .isEqualTo(JSON.readTree("{\"enum\":[\"REFINED\",\"DROPPED\"]}"));
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void statusSendsThePayloadUnchangedToTheEntityAsGiven() throws Exception {
        Result r = runWithInput("{\"target\":\"REFINED\"}", "work", "--entity", "qits-548", "status");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK + "/qits-548/status"))
                .isEqualTo(JSON.readTree("{\"target\":\"REFINED\"}"));
        assertThat(r.out()).contains("REFINED");
    }

    @Test
    void anArchetypeWithoutALifecycleIsAUsageErrorThatSaysSo() {
        // Every archetype has a lifecycle now; the guard still fires against an old service that has not
        // finished the migration and still serves one with none.
        platform.answer("GET", REGISTRY, ARCHETYPES_STALE_TASK);
        platform.answer("GET", WORK + "/qits-602", STALE_TASK_ROW);

        Result r = run("work", "--entity", "qits-602", "status");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("qits-602 is a TASK").contains("no lifecycle");

        Result piped = runWithInput("{\"target\":\"DONE\"}", "work", "--entity", "qits-602", "status");
        assertThat(piped.exit()).isEqualTo(2);
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void aRefusedStatusMoveIsTheServicesAnswer() {
        platform.answer("POST", WORK + "/qits-120/status", 403,
                "{\"message\":\"this credential is qits:agent, which reads but does not write\"}");
        Result forbidden = runWithInput("{\"target\":\"IMPLEMENTED\"}", "work", "--entity", "qits-120", "status");
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains("HTTP 403").contains("this credential is qits:agent");

        platform.answer("POST", WORK + "/qits-548/status", 409,
                "{\"message\":\"REPORTED cannot move to DONE\"}");
        Result illegal = runWithInput("{\"target\":\"DONE\"}", "work", "--entity", "qits-548", "status");
        assertThat(illegal.exit()).isEqualTo(1);
        assertThat(illegal.err()).contains("HTTP 409").contains("REPORTED cannot move to DONE");
    }

    // --- list and details ---

    @Test
    void listPrintsATableWithBlockedAndPassesTheFiltersInCapitals() {
        Result r = run("work", "list", "--project", "qits", "--archetype", "ticket", "--status", "reported");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", LIST).getFirst().query()).isEqualTo("archetype=TICKET&status=REPORTED");
        List<String> lines = r.out().lines().toList();
        assertThat(lines.getFirst()).startsWith("ID").contains("ARCHETYPE").contains("STATUS").contains("BLOCKED")
                .contains("TITLE").contains("UPDATED");
        assertThat(lines.get(1)).startsWith("qits-548").contains("TICKET").contains("REPORTED").contains("yes");
        assertThat(lines.get(2)).startsWith("qits-120").contains("EPIC").contains("The epic");
        assertThat(r.out()).doesNotContain(String.valueOf(ESC));
    }

    @Test
    void listShowsWaitingForAnAgentOnlyBlockAndADashWhenThereIsNone() {
        platform.answer("GET", LIST, "{\"entities\":[" + AGENT_WAITING_ROW + "," + EXPLICIT_BLOCK_ROW + ","
                + BOTH_BLOCK_ROW + "," + NOT_BLOCKED_ROW + "]}");

        Result r = run("work", "list", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        List<String> lines = r.out().lines().toList();
        // The BLOCKED column, not whatever else on the line happens to say the same word.
        assertThat(blockedColumn(lines.get(1))).isEqualTo("waiting");
        assertThat(blockedColumn(lines.get(2))).isEqualTo("yes");
        assertThat(blockedColumn(lines.get(3))).isEqualTo("yes");
        assertThat(blockedColumn(lines.get(4))).isEqualTo("-");
    }

    /** The BLOCKED column of a `work list` row: ID, ARCHETYPE, STATUS, BLOCKED, TITLE, UPDATED. */
    private static String blockedColumn(String row) {
        return row.strip().split("\\s{2,}")[3];
    }

    @Test
    void listByParentPassesTheQualifiedIdThrough() {
        Result r = run("work", "list", "--project", "qits", "--parent", "qits-548", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", LIST).getFirst().query()).isEqualTo("parent=qits-548");
        assertThat(platform.requests("GET", WORK + "/qits-548")).isEmpty();
        assertThat(r.out()).doesNotContain(String.valueOf(ESC)).contains("\\u001B[2J");
    }

    @Test
    void listWithoutAProjectIsAUsageError() {
        Result r = run("work", "list");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--project");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void detailsShowsTheItemItsThreadAndItsChildren() throws Exception {
        platform.answer("GET", WORK + "/qits-548/children", "{\"children\":[" + FEATURE_ROW + "]}");

        Result r = run("work", "--entity", "qits-548", "details");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", WORK + "/qits-548/children")).hasSize(1);
        assertThat(r.out()).contains("TICKET qits-548  (" + TICKET + ")")
                .contains("impetus").contains("The CLI drifted.")
                .contains("Description:").contains("The brief.")
                .contains("Comments (1):").contains("carol").contains("Seen")
                .contains("Children (1):").contains("qits-601").contains("Retry")
                .doesNotContain(String.valueOf(ESC));

        Result json = run("work", "--entity", "qits-548", "details", "-o", "json");
        JsonNode all = JSON.readTree(json.out());
        assertThat(all.path("entity").path("id").asText()).isEqualTo(TICKET);
        assertThat(all.path("comments").get(0).path("author").asText()).isEqualTo("carol");
        assertThat(all.path("children").get(0).path("qualifiedId").asText()).isEqualTo("qits-601");
    }

    @Test
    void detailsNumbersEachAcceptanceCriterionAndOmitsTheSectionWhenThereAreNone() {
        Result epic = run("work", "--entity", "qits-120", "details");

        assertThat(epic.exit()).as(epic.err()).isZero();
        assertThat(epic.out()).contains("Acceptance criteria:")
                .contains("1. Loads under 200ms")
                .contains("2. Shows an empty state when there is nothing");

        Result ticket = run("work", "--entity", "qits-548", "details");
        assertThat(ticket.exit()).as(ticket.err()).isZero();
        assertThat(ticket.out()).doesNotContain("Acceptance criteria:");
    }

    @Test
    void detailsDescribesAnAgentOnlyBlockAsWaitingForAPerson() {
        Result r = detailsOf("qits-700", AGENT_WAITING_ROW);
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("yes (agent waiting)");
    }

    @Test
    void detailsDescribesAnExplicitBlockWithItsReasonAndWho() {
        Result r = detailsOf("qits-701", EXPLICIT_BLOCK_ROW);
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("yes: Needs security sign-off (by alice)");
    }

    @Test
    void detailsOmitsTheReasonAndWhoAnExplicitBlockDoesNotCarry() {
        Result r = detailsOf("qits-702", EXPLICIT_BLOCK_BARE_ROW);
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("yes");
    }

    @Test
    void detailsCombinesTheExplicitFormWithAgentWaitingForBoth() {
        Result r = detailsOf("qits-703", BOTH_BLOCK_ROW);
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("yes: Needs security sign-off (by alice), agent waiting");
    }

    @Test
    void detailsSaysNoWhenTheItemIsSentNotBlocked() {
        Result r = detailsOf("qits-704", NOT_BLOCKED_ROW);
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("no");
    }

    @Test
    void detailsKeepsThePlainYesAnOlderServiceWithNoBlockSourceSent() {
        // TICKET_ROW is blocked:true with no blockSource at all, as an old, not-yet-migrated service sends it.
        Result r = run("work", "--entity", "qits-548", "details");
        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(blockedRow(r.out())).isEqualTo("yes");
    }

    /** `work --entity <id> details` against a freshly answered item, with empty comments and children. */
    private Result detailsOf(String qualifiedId, String row) {
        platform.answer("GET", WORK + "/" + qualifiedId, row);
        platform.answer("GET", WORK + "/" + qualifiedId + "/comments", "{\"entries\":[]}");
        platform.answer("GET", WORK + "/" + qualifiedId + "/children", "{\"children\":[]}");
        return run("work", "--entity", qualifiedId, "details");
    }

    /** The value of the details table's "blocked" row, with no padding around it. */
    private static String blockedRow(String out) {
        return out.lines().filter(line -> line.strip().startsWith("blocked")).findFirst()
                .orElseThrow(() -> new AssertionError("no blocked row in:%n%s".formatted(out)))
                .strip().replaceFirst("^blocked\\s+", "");
    }

    @Test
    void anUnknownEntityIsTheServicesAnswer() {
        Result r = run("work", "--entity", "qits-9999", "details");
        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 404");
    }

    @Test
    void theOldGroupsAreGone() {
        assertThat(run("ticket", "list").exit()).isEqualTo(2);
        assertThat(run("epic", "list").exit()).isEqualTo(2);
    }

    // --- help ---

    @Test
    void theWalkInHelpNamesReadyForDevAndTheHumanApprovalItNeeds() {
        Result group = run("work", "--help");
        assertThat(group.exit()).isZero();
        // picocli wraps the prose at the terminal width, turning some of the spaces this checks
        // for into line breaks; a single space normalizes either back to the words that matter.
        String groupText = group.out().replace('\n', ' ');
        assertThat(groupText).contains("REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED")
                .contains("READY_FOR_DEV move straight to IMPLEMENTED")
                .contains("IMPLEMENTING only moves forward or drops; it never moves back.")
                .contains("REFINED to READY_FOR_DEV needs a person")
                .contains("an agent credential is refused");

        Result status = run("work", "status", "--help");
        assertThat(status.exit()).isZero();
        String statusText = status.out().replace('\n', ' ');
        assertThat(statusText).contains("REPORTED, REFINED, READY_FOR_DEV, IMPLEMENTING, IMPLEMENTED")
                .contains("READY_FOR_DEV move straight to IMPLEMENTED")
                .contains("IMPLEMENTING only moves forward or drops; it never moves back.")
                .contains("REFINED to READY_FOR_DEV needs a person")
                .contains("an agent credential is refused (HTTP 409, or HTTP 403 for an epic).");
    }
}
