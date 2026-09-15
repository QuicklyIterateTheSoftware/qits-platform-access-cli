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

import java.io.ByteArrayOutputStream;
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

/** The commands as a person types them, in this process, against a fake platform and a fake idp. */
class PlatformCommandsTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QITS = "8f1c2d3e-0000-4000-8000-000000000001";
    private static final String DEMO = "8f1c2d3e-0000-4000-8000-000000000002";
    private static final String CI = "0a0b0c0d-0000-4000-8000-00000000000a";
    private static final String CI_FRONTEND = "0a0b0c0d-0000-4000-8000-00000000000b";
    private static final String REQUESTS = "/projects/api/repositories/" + CI + "/release-requests";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private SessionFile store;
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
        store = new SessionFile(home.resolve("qits"));
        env.put("XDG_CONFIG_HOME", home.toString());
        env.put("QITS_PROJECTS_URL", platform.url());
        seed(Duration.ofMinutes(10));

        platform.answer("GET", "/projects/api/projects", """
                {"entries":[
                  {"project":{"id":"%s","name":"qits platform","slug":"qits","description":"d","dns":null}},
                  {"project":{"id":"%s","name":"Demo","slug":"demo","description":null,"dns":null}}]}
                """.formatted(QITS, DEMO));
        platform.answer("GET", "/projects/api/projects/" + QITS + "/repositories", """
                {"entries":[
                  {"repository":{"id":"%s","name":"qits-ci-service","archetype":"QUARKUS_SERVICE","component":"qits-ci","projectId":"%s"},"declared":true},
                  {"repository":{"id":"%s","name":"qits-ci-frontend","archetype":"ANGULAR_FRONTEND","component":"qits-ci","projectId":"%s"},"declared":true}],
                 "wrapper":{"repositoryId":"w"}}
                """.formatted(CI, QITS, CI_FRONTEND, QITS));
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"11111111-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"PENDING","priority":"HIGH",
                   "summary":"Ship the\\nnew log view","version":null,"updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"22222222-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"RELEASED","priority":"MEDIUM",
                   "summary":"Cut, still gating","version":"2026.911.1","updatedAt":"2026-09-11T09:00:00Z"},
                  {"id":"33333333-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"REJECTED","priority":"MEDIUM",
                   "summary":"Red build","version":null,"updatedAt":"2026-09-12T08:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED","priority":"MEDIUM",
                   "summary":"Old release","version":"2026.910.1","updatedAt":"2026-09-10T09:00:00Z"}]}
                """);
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/logs","ref":"refs/heads/feature/logs","implicit":false,"priority":"HIGH","addedBy":"wohlben"}]}}
                """.formatted(CI));
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
        assertThat(allOut.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Session seed(Duration accessLeft) throws Exception {
        Session session = new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-0", T0.plus(accessLeft),
                idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        store.write(session);
        return session;
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), java.io.InputStream.nullInputStream(),
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
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        allOut.writeBytes(out.toByteArray());
        allErr.writeBytes(err.toByteArray());
        return result;
    }

    // --- projects ---

    @Test
    void projectsListIsATable() {
        Result r = run("projects", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "SLUG  NAME           ID",
                "qits  qits platform  " + QITS,
                "demo  Demo           " + DEMO);
        FakePlatform.Request request = platform.requests.getFirst();
        assertThat(request.authorization()).isEqualTo("Bearer " + FakeIdp.SECRET + "access-0");
        assertThat(request.accept()).isEqualTo("application/json");
    }

    @Test
    void jsonOutputIsTheServicesAnswer() throws Exception {
        Result r = run("projects", "list", "--output", "json");

        assertThat(r.exit()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("entries").get(1).path("project").path("slug").asText()).isEqualTo("demo");
        assertThat(run("projects", "-o", "json", "list").out()).isEqualTo(r.out());
        assertThat(run("projects", "list", "-o", "yaml").exit()).isEqualTo(2);
    }

    // --- repositories and the project's name ---

    @Test
    void theProjectIsFoundByIdSlugOrName() {
        for (String project : List.of(QITS, "qits", "qits platform")) {
            Result r = run("repositories", "--project", project, "list");
            assertThat(r.exit()).as(project).isZero();
            assertThat(r.out().lines().toList()).containsExactly(
                    "NAME              ARCHETYPE         COMPONENT  ID",
                    "qits-ci-service   QUARKUS_SERVICE   qits-ci    " + CI,
                    "qits-ci-frontend  ANGULAR_FRONTEND  qits-ci    " + CI_FRONTEND);
        }
        assertThat(platform.requests("GET", "/projects/api/projects/" + QITS + "/repositories")).hasSize(3);
    }

    @Test
    void theProjectMayComeAfterTheSubcommand() {
        assertThat(run("repositories", "list", "--project", "qits").out()).contains("qits-ci-service");
        assertThat(run("release-request", "--project", "qits", "list", "--repository", "qits-ci-service").out())
                .contains("PENDING");
        assertThat(run("release-request", "list", "--project", "qits", "--repository", CI).out()).contains("PENDING");
    }

    @Test
    void anUnknownProjectNamesTheOnesThereAre() {
        Result r = run("repositories", "--project", "nope", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("No project has the id, slug or name 'nope'. Projects: qits, demo.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aNameThatFitsTwoProjectsAsksForTheId() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits"}},
                            {"project":{"id":"%s","name":"qits","slug":"legacy"}}]}
                """.formatted(QITS, DEMO));

        Result r = run("repositories", "--project", "qits", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'qits' names more than one project: qits (id " + QITS + "), legacy (id " + DEMO
                + "). Name it by id.");
    }

    @Test
    void theProjectIsRequired() {
        Result r = run("repositories", "list");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Name the project: --project <id, slug or name>.");
        assertThat(platform.requests).isEmpty();
    }

    // --- release requests ---

    @Test
    void theListShowsOpenRequestsOnly() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("ID        STATE     PRIORITY  SUMMARY").contains("11111111  PENDING   HIGH      Ship the new log view")
                .contains("22222222  RELEASED").contains("Cut, still gating")
                .contains("33333333  REJECTED").doesNotContain("FINALIZED").doesNotContain("Old release");
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isNull();
    }

    @Test
    void jsonOutputOfTheListLeavesTheFinalizedOutToo() throws Exception {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "-o", "json");

        assertThat(JSON.readTree(r.out()).path("requests")).extracting(n -> n.path("state").asText())
                .containsExactly("PENDING", "RELEASED", "REJECTED");
    }

    @Test
    void aStateIsPassedThroughAndNothingIsDropped() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "--state", "all");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("FINALIZED").contains("2026.910.1");
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
    }

    @Test
    void anUnknownRepositorySaysWhereToLook() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-cd", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Project qits has no repository with the id or name 'qits-cd'.")
                .contains("`qits repositories --project qits list`");
        assertThat(run("release-request", "--project", "qits", "list").err())
                .contains("Name the repository: --repository <id or name>.");
    }

    @Test
    void createSendsTheBranchSummaryAndPriorityAndPrintsTheAnswer() throws Exception {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--summary", "Ship the log view", "--priority", "high");

        assertThat(r.exit()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", REQUESTS).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree(
                "{\"branch\":\"feature/logs\",\"summary\":\"Ship the log view\",\"priority\":\"HIGH\"}"));
        assertThat(r.out()).startsWith("Release request 44444444-2222-3333-4444-555555555555\n")
                .contains("  state          PENDING")
                .contains("  approval       NOT_REQUIRED")
                .contains("  version        -")
                .contains("Sources:")
                .contains("BRANCH  feature/logs  refs/heads/feature/logs  named  HIGH      wohlben");
    }

    @Test
    void printsTheGatesAndWhatSupersededTheRequestWhenTheServiceSendsThem() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"OBSOLETE","priority":"HIGH","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":"99999999-2222-3333-4444-555555555555",
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "gates":[{"kind":"PUBLISH","state":"FAILED"},{"kind":"APPROVAL","state":"PASSED"}],
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--summary", "Ship the log view");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("  state          OBSOLETE")
                .contains("  superseded by  99999999")
                .contains("Gates:")
                .contains("KIND      STATE")
                .contains("PUBLISH   FAILED")
                .contains("APPROVAL  PASSED");
    }

    @Test
    void createWithoutAPriorityLeavesItToThePlatform() throws Exception {
        Result r = run("release-request", "create", "--project", "qits", "--repository", CI,
                "--branch", "main", "--summary", "Release main", "-o", "json");

        assertThat(r.exit()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", REQUESTS).getFirst().body()).has("priority")).isFalse();
        assertThat(JSON.readTree(r.out()).path("request").path("id").asText()).startsWith("44444444");
        assertThat(run("release-request", "create", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    // --- joining a branch to a request ---

    private static final String PENDING_ID = "11111111-2222-3333-4444-555555555555";
    private static final String RELEASED_ID = "22222222-2222-3333-4444-555555555555";

    private static String sourcesOf(String id) {
        return REQUESTS + "/" + id + "/sources";
    }

    /** The pending request, answered with a second branch on it. */
    private void answerJoin() {
        platform.answer("POST", sourcesOf(PENDING_ID), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGHER","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null},
                             {"kind":"BRANCH","name":"feature/search","ref":"refs/heads/feature/search","implicit":false,"priority":"HIGHER","addedBy":"wohlben"}]}}
                """.formatted(PENDING_ID, CI));
    }

    @Test
    void joinFindsTheRequestByTheStartOfItsIdAndSendsTheBranchAndPriority() throws Exception {
        answerJoin();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "1111", "--branch", "feature/search", "--priority", "higher");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        JsonNode sent = JSON.readTree(platform.requests("POST", sourcesOf(PENDING_ID)).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"branch\":\"feature/search\",\"priority\":\"HIGHER\"}"));
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          PENDING")
                .contains("  priority       HIGHER")
                .contains("  merged sha     abc123")
                .contains("Sources:");
        assertThat(r.out().lines().toList()).contains(
                "  KIND    NAME            REF                        HOW    PRIORITY  ADDED BY",
                "  BRANCH  main            refs/heads/main            named  MEDIUM    -",
                "  BRANCH  feature/search  refs/heads/feature/search  named  HIGHER    wohlben");
    }

    @Test
    void joinWithoutAPriorityLeavesTheStoredOneAloneAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerJoin();

        Result r = run("release-request", "join", "--request", PENDING_ID, "--branch", "feature/search",
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", sourcesOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"branch\":\"feature/search\"}"));
        assertThat(JSON.readTree(r.out()).path("request").path("sources")).extracting(s -> s.path("name").asText())
                .containsExactly("main", "feature/search");
        assertThat(run("release-request", "join", "--project", "qits", "--repository", CI, "--branch", "x").exit())
                .isEqualTo(2);
        assertThat(run("release-request", "join", "--project", "qits", "--repository", CI, "--request", "1111").exit())
                .isEqualTo(2);
    }

    @Test
    void aRequestStartThatFitsNoneSaysWhereToLook() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "9999", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request whose id starts with '9999'.")
                .contains("`qits release-request --project qits --repository qits-ci-service list --state all` shows them.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aRequestStartThatFitsTwoAsksForMore() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"abcd0001-0000-4000-8000-000000000000","state":"PENDING"},
                  {"id":"abcd0002-0000-4000-8000-000000000000","state":"READY"},
                  {"id":"ef000003-0000-4000-8000-000000000000","state":"PENDING"}]}
                """);

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "ABCD", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'ABCD' is the start of more than one release request of qits-ci-service: "
                + "abcd0001-0000-4000-8000-000000000000 (PENDING), abcd0002-0000-4000-8000-000000000000 (READY). "
                + "Give more of the id.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aReleasedRequestTakesNoMoreBranches() {
        platform.answer("POST", sourcesOf(RELEASED_ID), 409,
                "{\"message\":\"Release request " + RELEASED_ID + " is already RELEASED\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "2222", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request " + RELEASED_ID + " is RELEASED and takes no more branches (HTTP 409). "
                + "Open a new one with `qits release-request create`.");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a404SaysThereIsNoSuchRequestOrBranch() {
        platform.answer("POST", sourcesOf(PENDING_ID), 404,
                "{\"message\":\"Release request not found: " + PENDING_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "11111111", "--branch", "feature/search");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such request or branch. POST " + platform.url() + sourcesOf(PENDING_ID)
                + " answered HTTP 404: Release request not found: " + PENDING_ID);
    }

    // --- withdrawing a request ---

    private static String withdrawOf(String id) {
        return REQUESTS + "/" + id + "/withdraw";
    }

    /** The pending request, answered as withdrawn with the given detail. */
    private void answerWithdraw(String detail) {
        platform.answer("POST", withdrawOf(PENDING_ID), """
                {"request":{"id":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"WITHDRAWN","priority":"HIGH","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":"abc123","version":null,"detail":"%s",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(PENDING_ID, CI, detail));
    }

    @Test
    void withdrawFindsTheRequestByTheStartOfItsIdAndSendsTheReason() throws Exception {
        answerWithdraw("The log view moves to qits-observability");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "1111", "--reason", "  The log view moves to qits-observability ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(platform.requests("POST", withdrawOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"reason\":\"The log view moves to qits-observability\"}"));
        assertThat(r.out()).startsWith("Release request " + PENDING_ID + "\n")
                .contains("  state          WITHDRAWN")
                .contains("  detail         The log view moves to qits-observability")
                .contains("Sources:");
    }

    @Test
    void withdrawWithoutAReasonSendsAnEmptyBodyAndTakesItsOptionsAfterTheSubcommand() throws Exception {
        answerWithdraw("Withdrawn by wohlben");

        Result r = run("release-request", "withdraw", "--request", PENDING_ID,
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", withdrawOf(PENDING_ID)).getFirst().body()))
                .isEqualTo(JSON.readTree("{}"));
        assertThat(JSON.readTree(r.out()).path("request").path("state").asText()).isEqualTo("WITHDRAWN");
        assertThat(JSON.readTree(r.out()).path("request").path("detail").asText()).isEqualTo("Withdrawn by wohlben");
        assertThat(run("release-request", "withdraw", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    @Test
    void withdrawOfAStartThatFitsNoneSendsNothing() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "9999");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no release request whose id starts with '9999'.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void aReleasedRequestCannotBeWithdrawn() {
        platform.answer("POST", withdrawOf(RELEASED_ID), 409,
                "{\"message\":\"Release request " + RELEASED_ID + " is already RELEASED\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "2222");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request " + RELEASED_ID + " is RELEASED already and cannot be "
                + "withdrawn (HTTP 409).");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void a404OnWithdrawSaysThereIsNoSuchRequest() {
        platform.answer("POST", withdrawOf(PENDING_ID), 404,
                "{\"message\":\"Release request not found: " + PENDING_ID + "\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "11111111");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("No such request. POST " + platform.url() + withdrawOf(PENDING_ID)
                + " answered HTTP 404: Release request not found: " + PENDING_ID);
    }

    // --- refusals ---

    @Test
    void a401NamesTheChallenge() {
        platform.answer("GET", "/projects/api/projects", 401, "",
                "WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"the token has expired\"");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The platform refused the token (HTTP 401: invalid_token, the token has expired).");
    }

    @Test
    void a403SaysTheRolesDoNotAllowIt() {
        platform.answer("GET", "/projects/api/projects", 403, "");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void anotherStatusCarriesTheServicesMessage() {
        platform.answer("POST", REQUESTS, 409, "{\"message\":\"the request is already released\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "x", "--summary", "y");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("POST " + platform.url() + REQUESTS + " answered HTTP 409: the request is already released");
    }

    // --- the session ---

    @Test
    void withoutASessionItSaysToSignIn() throws Exception {
        java.nio.file.Files.delete(store.path());

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Not signed in — run `qits login`.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void anExpiringTokenIsRefreshedFirst() throws Exception {
        seed(Duration.ofSeconds(10));

        Result r = run("projects", "list");

        assertThat(r.exit()).isZero();
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        String fresh = store.read().orElseThrow().accessToken();
        assertThat(fresh).isNotEqualTo(FakeIdp.SECRET + "access-0");
        assertThat(platform.requests).allMatch(req -> req.authorization().equals("Bearer " + fresh));
    }

    @Test
    void theProjectsAddressComesFromTheIdpWhenNothingNamesIt() {
        env.remove("QITS_PROJECTS_URL");

        Result r = run("projects", "list");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Pass --projects-url or set QITS_PROJECTS_URL.");
        assertThat(run("projects", "list", "--projects-url", platform.url()).exit()).isZero();
    }

    // --- events, as far as a command goes ---

    @Test
    void eventsSendsTheFilterAsNames() {
        env.put("QITS_EVENTS_URL", platform.url());
        platform.streams.add(platform.refuse(403, ""));

        Result r = run("events", "--filter", " BuildSuccessful, BuildFailed ,");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(platform.requests("GET", "/events/api/stream").getFirst().query()).isEqualTo("names=BuildSuccessful,BuildFailed");
        assertThat(r.err()).contains("Your roles do not allow this (HTTP 403)");
    }

    @Test
    void eventsRefusesAPattern() {
        Result r = run("events", "--filter", "Build*");
        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("'Build*' is neither");
        assertThat(run("events", "--filter", " , ").err()).contains("--filter names no event");
    }
}
