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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code qits work} commands that took over the old MCP tools: block, unblock, delete, progress,
 * transition --map, and the dossier, members and design groups. Run as a person types them, in this
 * process, against a fake platform and a fake idp.
 */
class WorkSubresourceCommandsTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ITEM = "qits-100";
    private static final String WORK = "/projects/api/work/" + ITEM;
    private static final String OPENAPI = "/projects/q/openapi";
    private static final char ESC = 0x1B;

    private static final String DOCUMENT = """
            {"openapi":"3.1.0",
             "paths":{
               "/projects/api/work/{qualifiedId}/dossier":{"post":{"requestBody":{"content":{"application/json":
                 {"schema":{"type":"object","required":["title"],"properties":{"title":{"type":"string"}}}}}}}},
               "/projects/api/work/{qualifiedId}/dossier/{page}":{"put":{"requestBody":{"content":{"application/json":
                 {"schema":{"type":"object","required":["version"],"properties":{"version":{"type":"integer"}}}}}}}},
               "/projects/api/work/transition":{"post":{"requestBody":{"content":{"application/json":
                 {"schema":{"type":"object","additionalProperties":{"type":"object"}}}}}}}}}
            """;

    private static final String PAGE = """
            {"id":"p1","ticketId":"t1","slug":"data-flow","title":"Data flow","position":0,
             "body":"line one\\n\\u001b[2Jline two","version":3,"updatedAt":"2026-10-10T09:00:00Z"}
            """;

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

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
        platform.answer("GET", OPENAPI, DOCUMENT);
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
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
        String printed = out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
        assertThat(printed).doesNotContain(FakeIdp.SECRET);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private JsonNode sent(String method, String path) throws Exception {
        return JSON.readTree(platform.requests(method, path).getFirst().body());
    }

    // --- block, unblock, delete, progress ---

    @Test
    void blockSendsTheFlagAndTheReason() throws Exception {
        platform.answer("POST", WORK + "/blocked", """
                {"block":{"entityId":"t1","archetype":"TICKET","status":"REPORTED","blocked":true,
                  "blockSource":"EXPLICIT","blockReason":"Waiting for the DB team."}}
                """);

        Result r = run("work", "--entity", ITEM, "block", "--reason", "Waiting for the DB team.");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK + "/blocked"))
                .isEqualTo(JSON.readTree("{\"blocked\":true,\"reason\":\"Waiting for the DB team.\"}"));
        assertThat(r.out()).contains("EXPLICIT").contains("yes");
    }

    @Test
    void blockWithoutAReasonSendsNothing() {
        Result r = run("work", "--entity", ITEM, "block");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--reason");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void unblockSendsFalseAndNoReasonUnlessGiven() throws Exception {
        platform.answer("POST", WORK + "/blocked", "{\"block\":{\"blocked\":false}}");

        Result r = run("work", "unblock", "--entity", ITEM, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK + "/blocked")).isEqualTo(JSON.readTree("{\"blocked\":false}"));
    }

    @Test
    void deleteSendsADeleteToTheItem() {
        platform.answer("DELETE", WORK, "{\"success\":true}");

        Result r = run("work", "--entity", ITEM, "delete");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("DELETE", WORK)).hasSize(1);
        assertThat(r.out()).contains("Deleted qits-100.");
    }

    @Test
    void progressPrintsEachMemberWithItsState() {
        platform.answer("GET", WORK + "/progress", """
                {"progress":{"campaign":{"qualifiedId":"qits-100","title":"Q4","status":"REFINED"},
                  "evaluator":{"connected":true,"stalled":false,"consumerFailing":false},
                  "members":[{"position":0,"state":"WAITING","waitsFor":["qits-101"],
                    "entity":{"qualifiedId":"qits-102","status":"REFINED","title":"Export"}}]}}
                """);

        Result r = run("work", "--entity", ITEM, "progress");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("evaluator: connected").contains("qits-102").contains("WAITING")
                .contains("qits-101");
    }

    // --- transition --map ---

    @Test
    void transitionMapSendsThePayloadUnchangedAndPrintsEachItem() throws Exception {
        String map = "{\"qits-101\":{\"archetype\":\"EPIC\",\"title\":\"Export\",\"status\":\"REPORTED\"},"
                + "\"qits-102\":{\"archetype\":\"FEATURE\",\"title\":\"CSV\",\"status\":\"REPORTED\","
                + "\"membership\":{\"parent\":\"qits-101\"}}}";
        platform.answer("POST", "/projects/api/work/transition", """
                {"qits-101":{"qualifiedId":"qits-101","archetype":"EPIC","status":"REPORTED","title":"Export"},
                 "qits-102":{"qualifiedId":"qits-102","archetype":"FEATURE","status":"REPORTED","title":"CSV"}}
                """);

        Result r = runWithInput(map, "work", "transition", "--map");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", "/projects/api/work/transition")).isEqualTo(JSON.readTree(map));
        assertThat(platform.requests("GET", WORK)).as("--map reads no item").isEmpty();
        assertThat(r.out()).contains("qits-101").contains("qits-102").contains("FEATURE");
    }

    @Test
    void transitionMapRefusesAnEntityOrAnArchetype() {
        Result r = runWithInput("{}", "work", "--entity", ITEM, "transition", "--map");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--map takes no --entity");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void transitionMapWithNothingOnStdinPrintsTheRequestSchema() {
        Result r = run("work", "transition", "--map");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("POST /projects/api/work/transition")
                .contains("additionalProperties");
        assertThat(platform.requests("POST", "/projects/api/work/transition")).isEmpty();
    }

    // --- dossier ---

    @Test
    void dossierListPrintsThePagesInOrder() {
        platform.answer("GET", WORK + "/dossier", "{\"pages\":[" + PAGE + "]}");

        Result r = run("work", "--entity", ITEM, "dossier", "list");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("SLUG").contains("data-flow").contains("Data flow");
    }

    @Test
    void dossierGetPrintsTheBodyWithoutControlCharacters() {
        platform.answer("GET", WORK + "/dossier/data-flow", PAGE);

        Result r = run("work", "--entity", ITEM, "dossier", "get", "--page", "data-flow");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("line one").contains("line two").doesNotContain(String.valueOf(ESC));
    }

    @Test
    void dossierPutWithoutAPageAddsOne() throws Exception {
        platform.answer("POST", WORK + "/dossier", 201, PAGE);

        Result r = runWithInput("{\"title\":\"Data flow\",\"body\":\"x\"}", "work", "--entity", ITEM, "dossier", "put");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK + "/dossier")).isEqualTo(JSON.readTree("{\"title\":\"Data flow\",\"body\":\"x\"}"));
        assertThat(r.out()).contains("PAGE data-flow");
    }

    @Test
    void dossierPutWithAPageRewritesIt() throws Exception {
        platform.answer("PUT", WORK + "/dossier/data-flow", PAGE);

        Result r = runWithInput("{\"body\":\"y\",\"version\":3}", "work", "--entity", ITEM, "dossier", "put",
                "--page", "data-flow");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("PUT", WORK + "/dossier/data-flow")).isEqualTo(JSON.readTree("{\"body\":\"y\",\"version\":3}"));
    }

    @Test
    void dossierPutWithNothingOnStdinPrintsTheSchemaOfTheDoorItWouldUse() {
        Result add = run("work", "--entity", ITEM, "dossier", "put");
        Result rewrite = run("work", "--entity", ITEM, "dossier", "put", "--page", "data-flow");

        assertThat(add.exit()).as(add.err()).isZero();
        assertThat(add.out()).contains("Required: title.");
        assertThat(rewrite.exit()).as(rewrite.err()).isZero();
        assertThat(rewrite.out()).contains("Required: version.");
        assertThat(platform.requests.stream().filter(q -> !q.method().equals("GET"))).isEmpty();
    }

    @Test
    void dossierMoveAndDeleteNameThePage() throws Exception {
        platform.answer("POST", WORK + "/dossier/data-flow/move", PAGE);
        platform.answer("DELETE", WORK + "/dossier/data-flow", "{\"success\":true}");

        Result move = run("work", "--entity", ITEM, "dossier", "move", "--page", "data-flow", "--position", "2");
        Result delete = run("work", "--entity", ITEM, "dossier", "delete", "--page", "data-flow");

        assertThat(move.exit()).as(move.err()).isZero();
        assertThat(sent("POST", WORK + "/dossier/data-flow/move")).isEqualTo(JSON.readTree("{\"position\":2}"));
        assertThat(delete.exit()).as(delete.err()).isZero();
        assertThat(platform.requests("DELETE", WORK + "/dossier/data-flow")).hasSize(1);
    }

    @Test
    void dossierInlinePrintsTheMarkdownLine() throws Exception {
        platform.answer("POST", WORK + "/dossier-assets", """
                {"id":"s1","kind":"DESIGN","label":"Board","url":"/x","markdown":"![Board](/x)"}
                """);

        Result r = run("work", "--entity", ITEM, "dossier", "inline", "--source", "s1", "--kind", "design");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", WORK + "/dossier-assets"))
                .isEqualTo(JSON.readTree("{\"sourceId\":\"s1\",\"kind\":\"DESIGN\"}"));
        assertThat(r.out().strip()).isEqualTo("![Board](/x)");
    }

    // --- members ---

    private static final String MEMBER = """
            {"membershipId":"m1","position":0,"groups":[{"criteria":[]}],
             "entity":{"qualifiedId":"qits-101","archetype":"TICKET","status":"REFINED","title":"Export"}}
            """;

    @Test
    void membersListAddMoveRemoveAndCondition() throws Exception {
        platform.answer("GET", WORK + "/members", "{\"members\":[" + MEMBER + "]}");
        platform.answer("POST", WORK + "/members", 201, "{\"member\":" + MEMBER + "}");
        platform.answer("PUT", WORK + "/members/m1/position", "{\"members\":[" + MEMBER + "]}");
        platform.answer("DELETE", WORK + "/members/m1", "{\"members\":[]}");
        platform.answer("PUT", WORK + "/members/m1/condition", "{\"member\":" + MEMBER + "}");

        Result list = run("work", "--entity", ITEM, "members", "list");
        Result add = runWithInput("{\"entityId\":\"qits-101\"}", "work", "--entity", ITEM, "members", "add");
        Result move = run("work", "--entity", ITEM, "members", "move", "--member", "m1", "--position", "0");
        Result remove = run("work", "--entity", ITEM, "members", "remove", "--member", "m1");
        Result condition = runWithInput("{\"groups\":[]}", "work", "--entity", ITEM, "members", "condition",
                "--member", "m1");

        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(list.out()).contains("MEMBERSHIP").contains("m1").contains("qits-101").contains("1 group");
        assertThat(add.exit()).as(add.err()).isZero();
        assertThat(sent("POST", WORK + "/members")).isEqualTo(JSON.readTree("{\"entityId\":\"qits-101\"}"));
        assertThat(move.exit()).as(move.err()).isZero();
        assertThat(sent("PUT", WORK + "/members/m1/position")).isEqualTo(JSON.readTree("{\"position\":0}"));
        assertThat(remove.exit()).as(remove.err()).isZero();
        assertThat(remove.out()).contains("no members");
        assertThat(condition.exit()).as(condition.err()).isZero();
        assertThat(sent("PUT", WORK + "/members/m1/condition")).isEqualTo(JSON.readTree("{\"groups\":[]}"));
    }

    @Test
    void membersConditionWithoutAMemberSendsNothing() {
        Result r = runWithInput("{\"groups\":[]}", "work", "--entity", ITEM, "members", "condition");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--member");
        assertThat(platform.requests.stream().filter(q -> !q.method().equals("GET"))).isEmpty();
    }

    // --- design ---

    @Test
    void designReadsGoThroughTheItemsRefinement() {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":{\"id\":42}}");
        platform.answer("GET", "/projects/api/refinements/42/designs", """
                {"designs":[{"id":"d1","title":"Board","version":2,"htmlBytes":120,"inUse":true}]}
                """);
        platform.answer("GET", "/projects/api/refinements/42/designs/d1", """
                {"id":"d1","title":"Board","version":2,"htmlBytes":120,"html":"<html><body>hi</body></html>"}
                """);

        Result list = run("work", "--entity", ITEM, "design", "list");
        Result get = run("work", "--entity", ITEM, "design", "get", "--design", "d1");

        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(list.out()).contains("d1").contains("Board").contains("yes");
        assertThat(get.exit()).as(get.err()).isZero();
        assertThat(get.out()).contains("<html><body>hi</body></html>");
    }

    @Test
    void designPutAddsOrRewritesWithTheVersion() throws Exception {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":{\"id\":42}}");
        platform.answer("POST", "/projects/api/refinements/42/designs", 201, "{\"id\":\"d2\",\"title\":\"New\"}");
        platform.answer("PUT", "/projects/api/refinements/42/designs/d1", "{\"id\":\"d1\",\"version\":3}");

        Result add = runWithInput("{\"title\":\"New\",\"html\":\"<html></html>\"}", "work", "--entity", ITEM,
                "design", "put");
        Result rewrite = runWithInput("{\"title\":\"Board\",\"html\":\"<html></html>\",\"version\":2}", "work",
                "--entity", ITEM, "design", "put", "--design", "d1");

        assertThat(add.exit()).as(add.err()).isZero();
        assertThat(sent("POST", "/projects/api/refinements/42/designs"))
                .isEqualTo(JSON.readTree("{\"title\":\"New\",\"html\":\"<html></html>\"}"));
        assertThat(rewrite.exit()).as(rewrite.err()).isZero();
        assertThat(sent("PUT", "/projects/api/refinements/42/designs/d1").path("version").asInt()).isEqualTo(2);
    }

    @Test
    void anItemWithNoRefinementHasNoDesigns() {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":null}");

        Result r = run("work", "--entity", ITEM, "design", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No refinement is open for qits-100, so it has no designs.");
    }

    // --- refinement ---

    private static final String ROOM = """
            {"id":7,"entityId":"t1","label":"refining-export-invoices-as-csv","runtimeStatus":"STOPPED",
             "branch":"refining/export-invoices-as-csv"}
            """;

    @Test
    void refinementOpenOpensTheRoomThenBringsItsContainerUp() {
        platform.answer("POST", WORK + "/refinement", "{\"refinement\":" + ROOM + "}");
        platform.answer("POST", "/projects/api/refinements/7/ensure-container", "{\"refinement\":"
                + ROOM.replace("STOPPED", "PROVISIONING") + ",\"technicalProcessId\":\"proc-1\"}");

        Result r = run("work", "--entity", ITEM, "refinement", "open");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests.stream().map(q -> q.method() + " " + q.path()).toList())
                .containsSubsequence("POST " + WORK + "/refinement", "POST /projects/api/refinements/7/ensure-container");
        assertThat(r.out()).contains("ID").contains("ENTITY").contains("LABEL").contains("STATUS")
                .contains("7").contains("t1").contains("refining-export-invoices-as-csv").contains("PROVISIONING")
                .contains("proc-1");
    }

    @Test
    void refinementOpenAsJsonPrintsTheBringUpsAnswer() throws Exception {
        platform.answer("POST", WORK + "/refinement", "{\"refinement\":" + ROOM + "}");
        platform.answer("POST", "/projects/api/refinements/7/ensure-container",
                "{\"refinement\":" + ROOM + ",\"technicalProcessId\":\"proc-1\"}");

        Result r = run("work", "--entity", ITEM, "refinement", "open", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("technicalProcessId").asText()).isEqualTo("proc-1");
        assertThat(printed.path("refinement").path("id").asInt()).isEqualTo(7);
    }

    @Test
    void aRefusedOpenPrintsTheServicesMessageAndBringsNothingUp() {
        platform.answer("POST", WORK + "/refinement", 409,
                "{\"message\":\"Ticket qits-100 is REFINED - only a REPORTED ticket can be refined.\"}");

        Result r = run("work", "--entity", ITEM, "refinement", "open");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("409").contains("only a REPORTED ticket can be refined");
        assertThat(platform.requests.stream().filter(q -> q.path().endsWith("/ensure-container"))).isEmpty();
    }

    @Test
    void refinementShowPrintsTheRoom() {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":" + ROOM + "}");

        Result r = run("work", "--entity", ITEM, "refinement", "show");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("7").contains("t1").contains("refining-export-invoices-as-csv")
                .contains("STOPPED");
        assertThat(platform.requests.stream().filter(q -> q.method().equals("POST"))).isEmpty();
    }

    @Test
    void refinementShowOfAnItemWithNoRoomSaysSoAndSucceeds() throws Exception {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":null}");

        Result r = run("work", "--entity", ITEM, "refinement", "show");
        Result json = run("work", "--entity", ITEM, "refinement", "show", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().strip()).isEqualTo("No refinement is open for qits-100.");
        assertThat(json.exit()).as(json.err()).isZero();
        assertThat(JSON.readTree(json.out()).path("refinement").isNull()).isTrue();
    }

    @Test
    void refinementDiscardDiscardsTheItemsRoom() {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":" + ROOM + "}");
        platform.answer("POST", "/projects/api/refinements/7/discard", "{\"success\":true}");

        Result r = run("work", "--entity", ITEM, "refinement", "discard");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", "/projects/api/refinements/7/discard")).hasSize(1);
        assertThat(r.out()).contains("qits-100");
    }

    @Test
    void refinementDiscardOfAnItemWithNoRoomFailsAndDiscardsNothing() {
        platform.answer("GET", WORK + "/refinement", "{\"refinement\":null}");

        Result r = run("work", "--entity", ITEM, "refinement", "discard");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No refinement is open for qits-100.").doesNotContain("designs");
        assertThat(platform.requests.stream().filter(q -> q.path().endsWith("/discard"))).isEmpty();
    }
}
