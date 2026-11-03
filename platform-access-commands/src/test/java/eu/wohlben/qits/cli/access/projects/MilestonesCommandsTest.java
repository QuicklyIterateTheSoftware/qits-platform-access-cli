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
 * qits milestones list|details|create|rename|criteria|approve, qits work milestones list|attach|detach
 * and qits work members milestones (epic qits-615), as a person types them, in this process, against
 * a fake platform and a fake idp.
 */
class MilestonesCommandsTest {

    private static final Instant T0 = Instant.parse("2026-11-03T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final char ESC = 0x1B;

    private static final String PROJECT = "11111111-0000-4000-8000-000000000001";
    private static final String MILESTONE = "22222222-0000-4000-8000-000000000002";
    private static final String OTHER = "33333333-0000-4000-8000-000000000003";
    private static final String CRITERION = "44444444-0000-4000-8000-000000000004";
    private static final String MILESTONES = "/projects/api/milestones";
    private static final String PROJECT_MILESTONES = "/projects/api/projects/qits/milestones";
    private static final String ITEM = "qits-100";
    private static final String WORK = "/projects/api/work/" + ITEM;
    private static final String OPENAPI = "/projects/q/openapi";

    /**
     * An unmet milestone: one group of an APPROVAL criterion that does not hold, and one of an
     * ENTITY_STATUS criterion that does. Its name carries an escape sequence, as anybody can write one.
     */
    private static final String UNMET = """
            {"id":"%s","projectId":"%s","displayName":"bootstrap \\u001b[2Jtest node","createdAt":"2026-11-03T08:00:00Z",
             "satisfiedAt":null,"met":false,
             "groups":[{"id":"g1","position":0,"createdAt":"2026-11-03T08:00:00Z","satisfiedAt":null,
               "criteria":[{"id":"%s","kind":"APPROVAL","predicate":{},"seeded":false,
                 "createdAt":"2026-11-03T08:00:00Z","satisfiedAt":null,"evidence":null,"approval":null},
                {"id":"c2","kind":"ENTITY_STATUS","predicate":{"entityId":"qits-90","status":"DONE"},"seeded":false,
                 "createdAt":"2026-11-03T08:00:00Z","satisfiedAt":"2026-11-03T08:00:01Z",
                 "evidence":{"eventId":null,"signature":"CURRENT_STATE","summary":"qits-90 is DONE"},"approval":null}]}],
             "entities":[{"id":"e1","qualifiedId":"qits-100","archetype":"TICKET","title":"Bootstrap a fresh node",
               "status":"REPORTED"}],
             "memberships":[{"membershipId":"m1","campaignId":"c1","campaignQualifiedId":"qits-130",
               "campaignTitle":"Ship the bootstrap","entityId":"e1","entityQualifiedId":"qits-100","claimedAt":null,
               "generatedCondition":false}]}
            """.formatted(MILESTONE, PROJECT, CRITERION);

    /** A met milestone, approved by a person. */
    private static final String MET = """
            {"id":"%s","projectId":"%s","displayName":"arm64 runner","createdAt":"2026-11-03T08:00:00Z",
             "satisfiedAt":"2026-11-03T09:00:00Z","met":true,
             "groups":[{"id":"g2","position":0,"createdAt":"2026-11-03T08:00:00Z","satisfiedAt":"2026-11-03T09:00:00Z",
               "criteria":[{"id":"c3","kind":"APPROVAL","predicate":{},"seeded":false,"createdAt":"2026-11-03T08:00:00Z",
                 "satisfiedAt":"2026-11-03T09:00:00Z","evidence":null,
                 "approval":{"approvedBy":"dev","note":"Node 7 is up."}}]}],
             "entities":[],"memberships":[]}
            """.formatted(OTHER, PROJECT);

    private static final String BRIEF = """
            {"milestones":[{"id":"%s","displayName":"bootstrap test node","satisfiedAt":null},
              {"id":"%s","displayName":"arm64 runner","satisfiedAt":"2026-11-03T09:00:00Z"}]}
            """.formatted(MILESTONE, OTHER);

    private static final String MEMBER = """
            {"member":{"membershipId":"m1","position":0,"groups":[],"holdsStatus":true,
             "entity":{"qualifiedId":"qits-101","archetype":"TICKET","status":"REFINED","title":"Export"},
             "milestones":[{"id":"%s","displayName":"bootstrap test node","satisfiedAt":null},
               {"id":"%s","displayName":"arm64 runner","satisfiedAt":"2026-11-03T09:00:00Z"}]}}
            """.formatted(MILESTONE, OTHER);

    private static final String DOCUMENT = """
            {"openapi":"3.1.0","paths":{
              "/projects/api/projects/{project}/milestones":{"post":{"requestBody":{"content":{"application/json":{
                "schema":{"$ref":"#/components/schemas/MilestoneCreate"}}}}}},
              "/projects/api/milestones/{milestoneId}/groups":{"put":{"requestBody":{"content":{"application/json":{
                "schema":{"$ref":"#/components/schemas/MilestoneGroups"}}}}}}},
             "components":{"schemas":{
              "MilestoneCreate":{"type":"object","required":["displayName","groups"],"properties":{
                "displayName":{"type":"string"},"groups":{"type":"array"}}},
              "MilestoneGroups":{"type":"object","required":["groups"],"properties":{"groups":{"type":"array"}}}}}}
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

        platform.answer("GET", PROJECT_MILESTONES, "{\"milestones\":[" + UNMET + "," + MET + "]}");
        platform.answer("POST", PROJECT_MILESTONES, 201, "{\"milestone\":" + UNMET + "}");
        platform.answer("GET", MILESTONES + "/" + MILESTONE, "{\"milestone\":" + UNMET + "}");
        platform.answer("PATCH", MILESTONES + "/" + MILESTONE, "{\"milestone\":" + UNMET + "}");
        platform.answer("PUT", MILESTONES + "/" + MILESTONE + "/groups", "{\"milestone\":" + UNMET + "}");
        platform.answer("POST", MILESTONES + "/" + OTHER + "/criteria/c3/approve", "{\"milestone\":" + MET + "}");
        platform.answer("GET", WORK + "/milestones", BRIEF);
        platform.answer("PUT", WORK + "/milestones/" + OTHER, BRIEF);
        platform.answer("DELETE", WORK + "/milestones/" + MILESTONE, "{\"milestones\":[]}");
        platform.answer("PUT", WORK + "/members/m1/milestones", MEMBER);
        platform.answer("GET", OPENAPI, DOCUMENT);
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    // --- list ---

    @Test
    void listPrintsATableOfTheProjectsMilestones() {
        Result r = run("milestones", "list", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PROJECT_MILESTONES).getFirst().query()).isNull();
        List<String> lines = r.out().lines().toList();
        assertThat(lines.getFirst()).startsWith("ID").contains("MET").contains("SINCE").contains("CRITERIA")
                .contains("NEEDED BY").contains("NAME");
        String unmet = lines.stream().filter(l -> l.startsWith(MILESTONE)).findFirst().orElseThrow();
        assertThat(unmet).contains(" no ").contains("1/2").contains("qits-100").contains("bootstrap");
        String met = lines.stream().filter(l -> l.startsWith(OTHER)).findFirst().orElseThrow();
        assertThat(met).contains(" yes ").contains("1/1").contains("arm64 runner");
        assertThat(r.out()).doesNotContain(String.valueOf(ESC));
    }

    @Test
    void listNarrowsToTheUnmetOnes() {
        Result r = run("milestones", "list", "--project", "qits", "--met", "false");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PROJECT_MILESTONES).getFirst().query()).isEqualTo("met=false");
    }

    @Test
    void listRefusesAMetThatIsNeitherTrueNorFalse() {
        Result r = run("milestones", "list", "--project", "qits", "--met", "maybe");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--met is true or false");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void listWithoutAProjectIsAUsageErrorWithNoRequest() {
        Result r = run("milestones", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--project");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void listAsJsonIsTheServicesAnswer() throws Exception {
        Result r = run("milestones", "list", "--project", "qits", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out()).path("milestones")).hasSize(2);
    }

    // --- details ---

    @Test
    void detailsShowsTheConditionWithIdsDatesAndEvidenceAndWhatNeedsIt() {
        Result r = run("milestones", "--milestone", MILESTONE, "details");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("MILESTONE bootstrap").contains(MILESTONE)
                .contains("met").contains("no")
                .contains("Met when:")
                .contains("[ ] APPROVAL  id " + CRITERION)
                .contains("[x] ENTITY_STATUS qits-90 DONE  id c2")
                .contains("CURRENT_STATE: qits-90 is DONE")
                .contains("Needed by (1):").contains("qits-100").contains("Bootstrap a fresh node")
                .contains("Campaign members (1):").contains("qits-130").contains("membership m1")
                .contains("not claimed")
                .doesNotContain(String.valueOf(ESC));
    }

    @Test
    void detailsOfAMetMilestoneSaysSinceWhenAndWhoApproved() {
        platform.answer("GET", MILESTONES + "/" + OTHER, "{\"milestone\":" + MET + "}");

        Result r = run("milestones", "details", "--milestone", OTHER);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("yes, since").contains("(holds since")
                .contains("approved by dev: Node 7 is up.").contains("Needed by: no work item.")
                .doesNotContain("Campaign members");
    }

    @Test
    void detailsWithoutAMilestoneIsAUsageError() {
        Result r = run("milestones", "details");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--milestone");
        assertThat(platform.requests).isEmpty();
    }

    // --- create, rename, criteria, approve ---

    @Test
    void createSendsThePayloadUnchangedToTheProjectsDoor() throws Exception {
        String payload = "{\"displayName\":\"bootstrap test node\",\"groups\":[{\"criteria\":[{\"kind\":\"APPROVAL\","
                + "\"predicate\":{}}]}]}";
        Result r = runWithInput(payload, "milestones", "create", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", PROJECT_MILESTONES)).isEqualTo(JSON.readTree(payload));
        assertThat(r.out()).contains(MILESTONE).contains("id " + CRITERION);
    }

    @Test
    void createWithNothingPutInPrintsTheSchemaAndSendsNothing() {
        Result r = run("milestones", "create");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("nothing was sent").contains("Required: displayName, groups.")
                .contains("POST /projects/api/projects/{project}/milestones");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void createWithoutAProjectSendsNothing() {
        Result r = runWithInput("{\"displayName\":\"x\",\"groups\":[]}", "milestones", "create");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--project");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void renameSendsTheNewNameAsPlainJson() throws Exception {
        Result r = run("milestones", "--milestone", MILESTONE, "rename", "--name", "bootstrap test node (arm64)");

        assertThat(r.exit()).as(r.err()).isZero();
        FakePlatform.Request request = platform.requests("PATCH", MILESTONES + "/" + MILESTONE).getFirst();
        assertThat(JSON.readTree(request.body())).isEqualTo(JSON.readTree(
                "{\"displayName\":\"bootstrap test node (arm64)\"}"));
        assertThat(request.contentType()).startsWith("application/json");
    }

    @Test
    void renameWithoutANameSendsNothing() {
        Result r = run("milestones", "--milestone", MILESTONE, "rename");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--name");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void criteriaPutsTheWholeConditionUnchanged() throws Exception {
        String payload = "{\"groups\":[{\"criteria\":[{\"id\":\"" + CRITERION + "\",\"kind\":\"APPROVAL\","
                + "\"predicate\":{}}]}]}";
        Result r = runWithInput(payload, "milestones", "--milestone", MILESTONE, "criteria");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("PUT", MILESTONES + "/" + MILESTONE + "/groups")).isEqualTo(JSON.readTree(payload));
    }

    @Test
    void criteriaWithNothingPutInPrintsTheSchema() {
        Result r = run("milestones", "criteria");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).contains("Required: groups.").contains("PUT /projects/api/milestones/{milestone}/groups");
        assertThat(anyWrite()).isFalse();
    }

    @Test
    void approveSendsTheNoteAndPrintsTheMetMilestone() throws Exception {
        Result r = run("milestones", "--milestone", OTHER, "approve", "--criterion", "c3", "--note", "Node 7 is up.");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", MILESTONES + "/" + OTHER + "/criteria/c3/approve"))
                .isEqualTo(JSON.readTree("{\"note\":\"Node 7 is up.\"}"));
        assertThat(r.out()).contains("yes, since");
    }

    @Test
    void approveWithoutANoteSendsAnEmptyObject() throws Exception {
        Result r = run("milestones", "--milestone", OTHER, "approve", "--criterion", "c3");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("POST", MILESTONES + "/" + OTHER + "/criteria/c3/approve")).isEqualTo(JSON.readTree("{}"));
    }

    @Test
    void approveWithoutACriterionSendsNothing() {
        Result r = run("milestones", "--milestone", OTHER, "approve");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--criterion");
        assertThat(platform.requests).isEmpty();
    }

    // --- qits work milestones ---

    @Test
    void workMilestonesListAttachAndDetach() {
        Result list = run("work", "--entity", ITEM, "milestones", "list");
        Result attach = run("work", "--entity", ITEM, "milestones", "attach", "--milestone", OTHER);
        Result detach = run("work", "--entity", ITEM, "milestones", "detach", "--milestone", MILESTONE);

        assertThat(list.exit()).as(list.err()).isZero();
        assertThat(list.out().lines().toList().getFirst()).startsWith("ID").contains("MET").contains("NAME");
        assertThat(list.out().lines().filter(l -> l.startsWith(MILESTONE)).findFirst().orElseThrow())
                .contains(" no ").contains("bootstrap test node");
        assertThat(list.out().lines().filter(l -> l.startsWith(OTHER)).findFirst().orElseThrow())
                .contains(" yes ").contains("arm64 runner");
        assertThat(attach.exit()).as(attach.err()).isZero();
        assertThat(platform.requests("PUT", WORK + "/milestones/" + OTHER).getFirst().body()).isEmpty();
        assertThat(detach.exit()).as(detach.err()).isZero();
        assertThat(detach.out()).contains("The item needs no milestone.");
    }

    @Test
    void workMilestonesAttachWithoutAMilestoneSendsNothing() {
        Result r = run("work", "--entity", ITEM, "milestones", "attach");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--milestone");
        assertThat(platform.requests).isEmpty();
    }

    // --- qits work members milestones ---

    @Test
    void membersMilestonesSendsTheIdsInOrderAndShowsWhatTheMemberNeeds() throws Exception {
        Result r = run("work", "--entity", ITEM, "members", "milestones", "--member", "m1", "--milestone", MILESTONE,
                "--milestone", OTHER);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("PUT", WORK + "/members/m1/milestones")).isEqualTo(JSON.readTree(
                "{\"milestoneIds\":[\"" + MILESTONE + "\",\"" + OTHER + "\"]}"));
        assertThat(r.out().lines().toList().getFirst()).contains("NEEDS");
        assertThat(r.out()).contains("bootstrap test node").doesNotContain("arm64 runner");
    }

    @Test
    void membersMilestonesNoneSendsAnEmptyList() throws Exception {
        Result r = run("work", "--entity", ITEM, "members", "milestones", "--member", "m1", "--none");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(sent("PUT", WORK + "/members/m1/milestones")).isEqualTo(JSON.readTree("{\"milestoneIds\":[]}"));
    }

    @Test
    void membersMilestonesTakesEitherMilestonesOrNone() {
        Result neither = run("work", "--entity", ITEM, "members", "milestones", "--member", "m1");
        Result both = run("work", "--entity", ITEM, "members", "milestones", "--member", "m1", "--none",
                "--milestone", MILESTONE);

        assertThat(neither.exit()).isEqualTo(2);
        assertThat(neither.err()).contains("--milestone").contains("--none");
        assertThat(both.exit()).isEqualTo(2);
        assertThat(both.err()).contains("--none");
        assertThat(platform.requests).isEmpty();
    }

    // --- the help ---

    @Test
    void theHelpSaysWhenEachKindLatches() {
        Result r = run("milestones", "create", "--help");

        assertThat(r.exit()).isZero();
        String help = r.out().replaceAll("\\s+", " ");
        assertThat(help).contains("DEPLOYMENT_ACTIVE and SCM_RELEASE latch only on events after the criterion was "
                        + "made")
                .contains("it latches from the current state");
    }

    @Test
    void theHelpNeverSaysWaitingForAMilestone() {
        for (String[] args : List.of(new String[] {"milestones", "--help"}, new String[] {"work", "milestones", "--help"},
                new String[] {"work", "list", "--help"}, new String[] {"work", "details", "--help"})) {
            Result r = run(args);
            assertThat(r.exit()).isZero();
            assertThat(r.out().toLowerCase()).as(String.join(" ", args)).doesNotContain("waiting on a milestone")
                    .doesNotContain("waits on a milestone");
        }
    }

    // --- helpers ---

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
}
