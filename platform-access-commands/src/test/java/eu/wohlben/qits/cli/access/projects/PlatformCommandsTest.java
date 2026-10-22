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
import eu.wohlben.qits.cli.session.Credential;
import eu.wohlben.qits.cli.session.RequestCredential;
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
import java.util.Base64;
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
    private static final String DOCS_APP = "0a0b0c0d-0000-4000-8000-00000000000c";
    private static final String REQUESTS = "/projects/api/repositories/" + CI + "/release-requests";
    private static final String REPOSITORIES = "/projects/api/projects/" + QITS + "/repositories";
    private static final String PROJECT_REQUESTS = "/projects/api/projects/" + QITS + "/release-requests";

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
        platform.answer("GET", PROJECT_REQUESTS, """
                {"requests":[
                  {"id":"55555555-2222-3333-4444-555555555555","repoName":"qits-ci-frontend","state":"PENDING",
                   "approvalState":"WAITING","priority":"BLOCKING","summary":"Dark theme","version":null,
                   "updatedAt":"2026-09-12T09:30:00Z"},
                  {"id":"11111111-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"PENDING",
                   "approvalState":"NOT_REQUIRED","priority":"HIGH","summary":"Ship the log view","version":null,
                   "updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED",
                   "approvalState":"NOT_REQUIRED","priority":"MEDIUM","summary":"Old release","version":"2026.910.1",
                   "updatedAt":"2026-09-10T09:00:00Z"}]}
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
        return run(null, args);
    }

    /** As {@link #run}, but calling the platform with {@code credential} rather than the session. */
    private Result run(Credential credential, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext built = new CliContext(Map.copyOf(env), java.io.InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), time, time, TokenClient::new, stop -> { });
        CliContext context = credential == null ? built : built.withCredential(credential);
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

    // --- creating a repository ---

    /** The door's answer: the row it wrote, and where the wrapper now mounts it. */
    private void answerCreate() {
        platform.answer("POST", REPOSITORIES, """
                {"repository":{"id":"%s","name":"qits-docs-app","backupUrl":null,"mainBranch":"main",
                  "archetype":"APP","component":"qits-docs","projectId":"%s","lastBackup":null},
                 "projectId":"%s","wrapperPath":"components/qits-docs/qits-docs-app"}
                """.formatted(DOCS_APP, QITS, QITS));
    }

    @Test
    void createSendsTheNameAndComponentAndNeverAnArchetype() throws Exception {
        answerCreate();

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app", "--component", "qits-docs");

        assertThat(r.exit()).isZero();
        JsonNode sent = JSON.readTree(platform.requests("POST", REPOSITORIES).getFirst().body());
        assertThat(sent).isEqualTo(JSON.readTree("{\"name\":\"qits-docs-app\",\"component\":\"qits-docs\"}"));
        assertThat(sent.has("archetype")).as("the name decides the kind; a flag could contradict it").isFalse();
    }

    @Test
    void createPrintsTheArchetypeTheDoorDerivedAndTheWrapperPath() {
        answerCreate();

        Result r = run("repositories", "create", "qits-docs-app", "--project", "qits", "--component", "qits-docs");

        assertThat(r.exit()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "Repository qits-docs-app",
                "  archetype      APP",
                "  component      qits-docs",
                "  id             " + DOCS_APP,
                "  wrapper entry  components/qits-docs/qits-docs-app");
    }

    @Test
    void createWithoutAComponentSendsNoneAndLetsTheWrapperDecide() throws Exception {
        platform.answer("POST", REPOSITORIES, """
                {"repository":{"id":"%s","name":"qits-docs-app","archetype":"APP","component":null,"projectId":"%s"},
                 "projectId":"%s","wrapperPath":null}
                """.formatted(DOCS_APP, QITS, QITS));

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", REPOSITORIES).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"name\":\"qits-docs-app\"}"));
        assertThat(r.out().lines().toList()).containsExactly(
                "Repository qits-docs-app",
                "  archetype  APP",
                "  component  -",
                "  id         " + DOCS_APP);
    }

    @Test
    void createInJsonPrintsTheAnswerAsItCame() throws Exception {
        answerCreate();

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app", "--component", "qits-docs",
                "-o", "json");

        assertThat(r.exit()).isZero();
        JsonNode printed = JSON.readTree(r.out());
        assertThat(printed.path("repository").path("archetype").asText()).isEqualTo("APP");
        assertThat(printed.path("wrapperPath").asText()).isEqualTo("components/qits-docs/qits-docs-app");
    }

    @Test
    void aNameWithNoRoleSuffixSaysTheServiceMayNotKnowTheRoleYet() {
        platform.answer("POST", REPOSITORIES, 400, """
                {"message":"'qits-docs' carries no role suffix, so there is nothing to read the kind of component\
                 out of."}
                """);

        Result r = run("repositories", "--project", "qits", "create", "qits-docs");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("The service cannot tell what kind of component 'qits-docs' is (HTTP 400)")
                .contains("carries no role suffix")
                .contains("a role the live service has not been released with reads exactly like a missing one");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aRefusedCredentialSaysWhichRolesTheDoorTakes() {
        platform.answer("POST", REPOSITORIES, 403, "{\"message\":\"forbidden\"}");

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("HTTP 403")
                .contains("Creating a repository needs qits:admin or qits:agent")
                .contains("has not been released with that role on this door yet");
    }

    @Test
    void anotherRefusalIsLeftAsTheServiceWordedIt() {
        platform.answer("POST", REPOSITORIES, 400, "{\"message\":\"A repository named qits-docs-app exists.\"}");

        Result r = run("repositories", "--project", "qits", "create", "qits-docs-app");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("A repository named qits-docs-app exists.")
                .doesNotContain("role suffix");
    }

    @Test
    void creatingNeedsTheProjectAndTheName() {
        Result noProject = run("repositories", "create", "qits-docs-app");
        assertThat(noProject.exit()).isEqualTo(2);
        assertThat(noProject.err()).contains("Name the project: --project <id, slug or name>.");

        Result noName = run("repositories", "--project", "qits", "create");
        assertThat(noName.exit()).isEqualTo(2);
        assertThat(platform.requests("POST", REPOSITORIES)).isEmpty();
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
        assertThat(run("release-request", "--project", "qits", "create", "--branch", "b", "--summary", "s").err())
                .contains("Name the repository: --repository <id or name>.");
    }

    @Test
    void withoutARepositoryTheListShowsTheWholeProjectAsTheServiceAnswers() {
        Result r = run("release-request", "--project", "qits", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out())
                .contains("ID        REPOSITORY        STATE      APPROVAL      PRIORITY  SUMMARY")
                .contains("55555555  qits-ci-frontend  PENDING    WAITING       BLOCKING  Dark theme")
                .contains("11111111  qits-ci-service   PENDING")
                // The landing app's default: the last finalized stay, nothing is dropped.
                .contains("77777777  qits-ci-service   FINALIZED").contains("2026.910.1");
        assertThat(platform.requests("GET", PROJECT_REQUESTS).getFirst().query()).isNull();
        assertThat(platform.requests("GET", REQUESTS)).isEmpty();
        assertThat(platform.requests("GET", REPOSITORIES)).isEmpty();
    }

    @Test
    void theProjectListPassesTheStateAndCutsAtTheLimitInBothForms() throws Exception {
        Result r = run("release-request", "list", "--project", "qits", "--state", "all", "--limit", "2");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("55555555").contains("11111111").doesNotContain("77777777");
        assertThat(platform.requests("GET", PROJECT_REQUESTS).getFirst().query()).isEqualTo("state=all");

        Result json = run("release-request", "list", "--project", "qits", "--limit", "1", "-o", "json");
        assertThat(JSON.readTree(json.out()).path("requests")).extracting(n -> n.path("repoName").asText())
                .containsExactly("qits-ci-frontend");
    }

    @Test
    void theRepositoryListCutsAtTheLimitToo() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list", "--limit", "1");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("11111111").doesNotContain("22222222");
    }

    @Test
    void anEmptyProjectListNamesTheProjectAndALimitBelowOneIsRefused() {
        platform.answer("GET", PROJECT_REQUESTS, "{\"requests\":[]}");

        assertThat(run("release-request", "--project", "qits", "list").out())
                .contains("No open release requests for project qits.");
        Result refused = run("release-request", "--project", "qits", "list", "--limit", "0");
        assertThat(refused.exit()).isEqualTo(2);
        assertThat(refused.err()).contains("--limit must be 1 or more.");
        assertThat(run("release-request", "list").err()).contains("--project");
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
                  "gates":[{"kind":"PUBLISH","state":"FAILED"},
                           {"kind":"APPROVAL","state":"PASSED","detail":"configured by manual-review"}],
                  "sources":[{"kind":"BRANCH","name":"main","ref":"refs/heads/main","implicit":false,"priority":"MEDIUM","addedBy":null}]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--summary", "Ship the log view");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("  state          OBSOLETE")
                .contains("  superseded by  99999999")
                .contains("Gates:")
                .contains("KIND      STATE   REASON")
                .contains("PUBLISH   FAILED  -")
                .contains("APPROVAL  PASSED  configured by manual-review");
    }

    @Test
    void leavesTheGatesReasonBlankWhenAnOlderServiceOmitsIt() throws Exception {
        platform.answer("POST", REQUESTS, """
                {"request":{"id":"44444444-2222-3333-4444-555555555555","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"HIGH","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":null,
                  "createdAt":"2026-09-12T10:00:00Z","updatedAt":"2026-09-12T10:00:00Z",
                  "gates":[{"kind":"APPROVAL","state":"PASSED"}],
                  "sources":[]}}
                """.formatted(CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "create",
                "--branch", "feature/logs", "--summary", "Ship the log view");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).contains("Gates:")
                .contains("KIND      STATE   REASON")
                .contains("APPROVAL  PASSED  -");
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
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'. Give its id "
                        + "(qits-ci-service-rr-<n>), its uuid or the start of the uuid.")
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
                + "Give its id or more of the uuid.");
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

    // --- logical ids (qits-1158) ---

    /** The repository's requests as a qits-projects with logical ids answers them. */
    private void answerQualified() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"%s","qualifiedId":"qits-ci-service-rr-12","repoName":"qits-ci-service","state":"PENDING",
                   "priority":"HIGH","summary":"Ship the log view","version":null,"updatedAt":"2026-09-12T09:00:00Z"},
                  {"id":"%s","qualifiedId":"qits-ci-service-rr-1","repoName":"qits-ci-service","state":"RELEASED",
                   "priority":"MEDIUM","summary":"Cut, still gating","version":"2026.911.1","updatedAt":"2026-09-11T09:00:00Z"}]}
                """.formatted(PENDING_ID, RELEASED_ID));
    }

    @Test
    void theListShowsTheLogicalId() {
        answerQualified();

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "list");

        assertThat(r.exit()).isZero();
        assertThat(r.out()).startsWith("ID                     STATE     PRIORITY  SUMMARY            VERSION     UPDATED\n"
                + "qits-ci-service-rr-12  PENDING   HIGH      Ship the log view  -           2026-09-12 ");
        assertThat(r.out()).doesNotContain("11111111");
    }

    @Test
    void joinTakesTheLogicalIdInAnyCaseAndSendsTheUuid() throws Exception {
        answerQualified();
        platform.answer("POST", sourcesOf(PENDING_ID), """
                {"request":{"id":"%s","qualifiedId":"qits-ci-service-rr-12","repoId":"%s","repoName":"qits-ci-service",
                  "state":"OBSOLETE","priority":"HIGH","summary":"Ship the log view","requester":"wohlben",
                  "approvalState":"NOT_REQUIRED","mergedSha":null,"version":null,"detail":null,
                  "supersededBy":"99999999-2222-3333-4444-555555555555","supersededByQualifiedId":"qits-ci-service-rr-13",
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[]}}
                """.formatted(PENDING_ID, CI));

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "join",
                "--request", "QITS-CI-SERVICE-RR-12", "--branch", "feature/search");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("POST", sourcesOf(PENDING_ID))).hasSize(1);
        assertThat(r.out()).startsWith("Release request qits-ci-service-rr-12\n")
                .contains("  uuid           " + PENDING_ID)
                .contains("  superseded by  qits-ci-service-rr-13");
    }

    @Test
    void aLogicalIdWinsOverAUuidStartAndOneThatFitsNoneIsRefused() {
        answerQualified();
        platform.answer("POST", withdrawOf(RELEASED_ID), 409, "{\"message\":\"already RELEASED\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "qits-ci-service-rr-1");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Release request qits-ci-service-rr-1 is RELEASED already and cannot be withdrawn");
        assertThat(run("release-request", "--project", "qits", "--repository", "qits-ci-service", "withdraw",
                "--request", "qits-ci-service-rr-2").err())
                .contains("Repository qits-ci-service has no release request 'qits-ci-service-rr-2'.");
        assertThat(platform.requests("POST", withdrawOf(RELEASED_ID))).hasSize(1);
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
        assertThat(r.err()).contains("Repository qits-ci-service has no release request '9999'.");
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

    // --- approving and declining a fold ---

    private static final String DECIDED_ID = "66666666-2222-3333-4444-555555555555";
    private static final String DECIDED = "qits-ci-service-rr-221";
    private static final String FOLD = "aaa1111111111111111111111111111111111111";

    private static String decisionOf(String verb) {
        return REQUESTS + "/" + DECIDED_ID + "/" + verb;
    }

    /** The project's door answers the request with its repository and the fold it is on (or none). */
    private void answerProjectRequest(String ref, String mergedSha) {
        platform.answer("GET", PROJECT_REQUESTS + "/" + ref, """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","approvalState":"WAITING","mergedSha":%s,"summary":"Bump the estate"}}
                """.formatted(DECIDED_ID, DECIDED, CI, mergedSha == null ? "null" : "\"" + mergedSha + "\""));
    }

    /** The decision door's answer: the request as the decision left it. */
    private static String decided(String state, String approval, String sha) {
        return """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"%s","priority":"MEDIUM","summary":"Bump the estate","requester":"maintenance",
                  "approvalState":"%s","mergedSha":"%s","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[]}}
                """.formatted(DECIDED_ID, DECIDED, CI, state, approval, sha);
    }

    @Test
    void approveWithoutAShaFindsTheRequestInTheProjectAndApprovesTheFoldItRead() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", FOLD));

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", PROJECT_REQUESTS + "/" + DECIDED)).hasSize(1);
        assertThat(platform.requests("GET", REPOSITORIES)).as("no repository is looked up").isEmpty();
        assertThat(JSON.readTree(platform.requests("POST", decisionOf("approve")).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(r.out()).startsWith("Approved " + DECIDED + " at " + FOLD + ".\nRelease request " + DECIDED + "\n")
                .contains("  approval       APPROVED")
                .contains("  merged sha     " + FOLD);
    }

    @Test
    void approveWithAShaSendsThatShaAsGiven() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        String judged = "bbb2222222222222222222222222222222222222";
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", judged));

        Result r = run("release-request", "approve", "--project", "qits", "--request", DECIDED, "--sha", " " + judged + " ");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", decisionOf("approve")).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mergedSha\":\"" + judged + "\"}"));
        assertThat(r.out()).startsWith("Approved " + DECIDED + " at " + judged + ".\n");
    }

    @Test
    void aStaleFoldReachesThePersonInTheServicesWords() {
        answerProjectRequest(DECIDED, FOLD);
        String stale = "Release request x-rr-1 is on bbb now, not aaa; re-read it and approve the fold you are looking at.";
        platform.answer("POST", decisionOf("approve"), 409, "{\"message\":\"" + stale + "\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "--sha", "aaa");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("POST " + platform.url() + decisionOf("approve") + " answered HTTP 409: " + stale);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aRefusalOfWhoMayDecideReachesThePersonInTheServicesWords() {
        answerProjectRequest(DECIDED, FOLD);
        String personOnly = "Only a person may make this decision, verified here: a browser session or a person's "
                + "qits CLI, holding qits:admin. Asserted identity headers, service clients, agents, CI and "
                + "workstation tokens do not count.";
        platform.answer("POST", decisionOf("decline"), 403, "{\"message\":\"" + personOnly + "\"}");

        Result r = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(personOnly);
        assertThat(r.out()).isEmpty();
    }

    @Test
    void declineSendsItsNoteAndABlankOrMissingNoteIsLeftOut() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("decline"), decided("REJECTED", "DECLINED", FOLD));

        Result noted = run("release-request", "--project", "qits", "decline", "--request", DECIDED,
                "--note", "  The README promises an approval policy nobody has signed off yet. ");
        Result blank = run("release-request", "--project", "qits", "decline", "--request", DECIDED, "--note", "  ");
        Result none = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(List.of(noted, blank, none)).allSatisfy(r -> assertThat(r.exit()).as(r.err()).isZero());
        List<FakePlatform.Request> sent = platform.requests("POST", decisionOf("decline"));
        assertThat(sent).hasSize(3);
        assertThat(JSON.readTree(sent.get(0).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD
                + "\",\"note\":\"The README promises an approval policy nobody has signed off yet.\"}"));
        assertThat(JSON.readTree(sent.get(1).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(JSON.readTree(sent.get(2).body())).isEqualTo(JSON.readTree("{\"mergedSha\":\"" + FOLD + "\"}"));
        assertThat(none.out()).startsWith("Declined " + DECIDED + " at " + FOLD + ".\n")
                .contains("  state          REJECTED");
    }

    @Test
    void aRequestWithNoFoldIsRefusedHereAndNothingIsSent() {
        answerProjectRequest(DECIDED, null);

        Result approve = run("release-request", "--project", "qits", "approve", "--request", DECIDED);
        Result decline = run("release-request", "--project", "qits", "decline", "--request", DECIDED);

        assertThat(approve.exit()).isNotZero();
        assertThat(approve.err()).contains("Release request " + DECIDED
                + " has not been folded yet, so there is no fold to approve.");
        assertThat(decline.exit()).isNotZero();
        assertThat(decline.err()).contains("there is no fold to decline.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    @Test
    void jsonPrintsTheServicesAnswerUnchanged() throws Exception {
        answerProjectRequest(DECIDED, FOLD);
        String answer = decided("PENDING", "APPROVED", FOLD);
        platform.answer("POST", decisionOf("approve"), answer);

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(answer));
        assertThat(r.out()).doesNotContain("Approved ");
    }

    // --- approval requirements (epic qits-1038) ---

    /** The project's door, with one open {@code approvalRequirements} entry on the request it reads. */
    private void answerProjectRequestAwaitingApproval() {
        platform.answer("GET", PROJECT_REQUESTS + "/" + DECIDED, """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","approvalState":"WAITING","mergedSha":"%s","summary":"Bump the estate",
                  "approvalRequirements":[
                    {"id":"manual-review","reason":"configured by manual-review","admits":["person"],
                     "excludesAuthors":false,"detail":null,"satisfied":false,"satisfiedBy":null}]}}
                """.formatted(DECIDED_ID, DECIDED, CI, FOLD));
    }

    /** The decision door's answer, with that same requirement now satisfied. */
    private static String approvedWithRequirement() {
        return """
                {"request":{"id":"%s","qualifiedId":"%s","repoId":"%s","repoName":"qits-ci-service",
                  "state":"PENDING","priority":"MEDIUM","summary":"Bump the estate","requester":"maintenance",
                  "approvalState":"APPROVED","mergedSha":"%s","version":null,"detail":null,
                  "createdAt":"2026-09-12T09:00:00Z","updatedAt":"2026-09-12T10:00:00Z","sources":[],
                  "approvalRequirements":[
                    {"id":"manual-review","reason":"configured by manual-review","admits":["person"],
                     "excludesAuthors":false,"detail":null,"satisfied":true,
                     "satisfiedBy":{"approver":"dev","roles":["person"],"at":"2026-09-12T10:00:00Z"}}]}}
                """.formatted(DECIDED_ID, DECIDED, CI, FOLD);
    }

    @Test
    void approveWithRequirementsPrintsThemBeforeAndAfterTheDecision() throws Exception {
        answerProjectRequestAwaitingApproval();
        platform.answer("POST", decisionOf("approve"), approvedWithRequirement());

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).containsSubsequence("Requirements before approve:",
                "manual-review", "configured by manual-review", "Approved " + DECIDED,
                "Requirements after approve:", "manual-review", "configured by manual-review");
        // Before the decision nothing has satisfied it yet.
        assertThat(r.out()).containsPattern(
                "manual-review\\s+configured by manual-review\\s+person\\s+no\\s+no\\s+-");
        // After the decision the answered request reports it satisfied, and by whom.
        assertThat(r.out()).containsPattern(
                "manual-review\\s+configured by manual-review\\s+person\\s+no\\s+yes\\s+dev \\(person\\) at");
    }

    @Test
    void jsonWithRequirementsPrintsTheServicesAnswerUnchanged() throws Exception {
        answerProjectRequestAwaitingApproval();
        String answer = approvedWithRequirement();
        platform.answer("POST", decisionOf("approve"), answer);

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree(answer));
        assertThat(r.out()).doesNotContain("Requirements before").doesNotContain("Requirements after");
    }

    @Test
    void anOlderServiceWithNoApprovalRequirementsFieldPrintsNothingExtra() throws Exception {
        // answerProjectRequest/decided (above) carry no approvalRequirements at all: an older
        // provider's shape. Table output stays exactly what it was before this epic.
        answerProjectRequest(DECIDED, FOLD);
        platform.answer("POST", decisionOf("approve"), decided("PENDING", "APPROVED", FOLD));

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).doesNotContain("Requirements");
    }

    @Test
    void a403NamingTheOpenRequirementsReachesThePersonVerbatim() {
        answerProjectRequest(DECIDED, FOLD);
        String refusal = "You may not approve release request " + DECIDED + " at " + FOLD.substring(0, 10)
                + ": its open approval requirements are manual-review (configured by manual-review) admits person."
                + " You hold no approval role.";
        platform.answer("POST", decisionOf("approve"), 403, "{\"message\":\"" + refusal + "\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(refusal);
        assertThat(r.out()).isEmpty();
    }

    /** An unsigned JWT naming {@code qits:agent}: enough for {@link RequestCredential} to read it. */
    private static String agentToken() {
        Base64.Encoder base64 = Base64.getUrlEncoder().withoutPadding();
        String claims = "{\"sub\":\"workspace-7f3a\",\"aud\":\"qits-platform\",\"groups\":[\"qits:agent\"]}";
        return base64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + base64.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    /**
     * The defect this replaced (qits-1038, verified live on qits-1044): an agent approving a
     * request whose open requirement admits only {@code person} used to see "this credential is
     * qits:agent, which reads but does not write" above the door's own sentence — wrong, because
     * qits-projects admits {@code qits:agent} at this door and decides eligibility itself; a 403
     * here means "you hold no admitted role", not "agents only read". The door's sentence now
     * reaches the agent exactly as it reached the person in the test above, with no hint pasted
     * over it.
     */
    @Test
    void a403ToAnAgentCredentialPrintsTheDoorsSentenceWithoutTheReadOnlyHint() {
        answerProjectRequest(DECIDED, FOLD);
        String refusal = "You may not approve release request " + DECIDED + " at " + FOLD.substring(0, 10)
                + ": its open approval requirements are manual-review (configured by manual-review) admits person."
                + " You hold agent.";
        platform.answer("POST", decisionOf("approve"), 403, "{\"message\":\"" + refusal + "\"}");

        Result r = run(new RequestCredential(agentToken()), "release-request", "--project", "qits", "approve",
                "--request", DECIDED);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains(refusal)
                .doesNotContain("reads but does not write");
        assertThat(r.out()).isEmpty();
    }

    @Test
    void aRequestTheProjectDoesNotHoldIsTheServices404() {
        platform.answer("GET", PROJECT_REQUESTS + "/qits-ci-service-rr-9", 404,
                "{\"message\":\"Project " + QITS + " has no release request qits-ci-service-rr-9\"}");

        Result r = run("release-request", "--project", "qits", "approve", "--request", "qits-ci-service-rr-9");

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("answered HTTP 404: Project " + QITS + " has no release request qits-ci-service-rr-9");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
    }

    // --- regenerating a release output ---

    private static final String REJECTED_ID = "33333333-2222-3333-4444-555555555555";

    private static String runsOf(String id, String kind) {
        return REQUESTS + "/" + id + "/automations/" + kind + "/runs";
    }

    /** The repository with one open request, the REJECTED one; the finalized one does not count. */
    private void answerOneOpenRequest() {
        platform.answer("GET", REQUESTS, """
                {"requests":[
                  {"id":"%s","repoName":"qits-ci-service","state":"REJECTED","priority":"MEDIUM",
                   "summary":"Red build","version":null,"updatedAt":"2026-09-12T08:00:00Z"},
                  {"id":"77777777-2222-3333-4444-555555555555","repoName":"qits-ci-service","state":"FINALIZED","priority":"MEDIUM",
                   "summary":"Old release","version":"2026.910.1","updatedAt":"2026-09-10T09:00:00Z"}]}
                """.formatted(REJECTED_ID));
    }

    @Test
    void regenerateWithoutARequestTakesTheOneOpenRequestEvenWhenItIsRejected() {
        answerOneOpenRequest();
        platform.answer("POST", runsOf(REJECTED_ID, "entity-diagram"), 202, "{\"id\":\"bump-1\"}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "entity-diagram");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isNull();
        assertThat(platform.requests("POST", runsOf(REJECTED_ID, "entity-diagram")).getFirst().body()).isEmpty();
        assertThat(r.out().lines().toList()).containsExactly(
                "Asked for entity-diagram on release request " + REJECTED_ID + ": run bump-1",
                "The request folds again when the output is committed to maintenance/automations/entity-diagram/"
                        + REJECTED_ID + "; QA then runs again.",
                "Follow the run with `qits maintenance bump bump-1`.");
    }

    @Test
    void regenerateFindsANamedRequestByTheStartOfItsIdAndPrintsJson() throws Exception {
        platform.answer("POST", runsOf(PENDING_ID, "screenshot-baselines"), 202, "{\"id\":\"bump-2\"}");

        Result r = run("release-request", "regenerate", "--request", "1111", "--kind", "Screenshot-Baselines",
                "--project", "qits", "--repository", CI, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(platform.requests("GET", REQUESTS).getFirst().query()).isEqualTo("state=all");
        assertThat(JSON.readTree(r.out())).isEqualTo(JSON.readTree("{\"id\":\"bump-2\"}"));
    }

    @Test
    void regenerateWithoutARequestRefusesWhenMoreThanOneIsOpen() {
        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "estate-pins");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has more than one open release request: "
                + "11111111 (PENDING), 33333333 (REJECTED). Name one with --request.");
        assertThat(platform.requests).noneMatch(req -> req.method().equals("POST"));
        assertThat(run("release-request", "regenerate", "--project", "qits", "--repository", CI).exit()).isEqualTo(2);
    }

    @Test
    void regenerateWithoutARequestRefusesWhenNoneIsOpen() {
        platform.answer("GET", REQUESTS, "{\"requests\":[]}");

        Result r = run("release-request", "--project", "qits", "--repository", "qits-ci-service", "regenerate",
                "--kind", "estate-pins");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("Repository qits-ci-service has no open release request. Name one with --request.");
    }

    @Test
    void regenerateTurnsTheDoorsRefusalsIntoSentences() {
        answerOneOpenRequest();
        platform.answer("POST", runsOf(REJECTED_ID, "estate-pins"), 409,
                "{\"message\":\"An estate-pins run is already active for this request\"}");
        platform.answer("POST", runsOf(REJECTED_ID, "nonsense"), 404, "{\"message\":\"Unknown kind: nonsense\"}");
        platform.answer("POST", runsOf(REJECTED_ID, "entity-diagram"), 503,
                "{\"message\":\"qits-maintenance is not configured\"}");

        Result busy = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "estate-pins");
        Result unknown = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "nonsense");
        Result down = run("release-request", "--project", "qits", "--repository", CI, "regenerate", "--kind",
                "entity-diagram");

        assertThat(busy.exit()).isEqualTo(1);
        assertThat(busy.err()).contains("The platform will not run estate-pins on release request " + REJECTED_ID
                        + " now (HTTP 409)")
                .contains("`qits maintenance automations --request 33333333`")
                .contains("An estate-pins run is already active for this request");
        assertThat(unknown.exit()).isEqualTo(1);
        assertThat(unknown.err()).contains("The platform knows no kind 'nonsense' or no release request "
                        + REJECTED_ID + " (HTTP 404). The kinds are entity-diagram, screenshot-baselines, "
                        + "dependency-bump and estate-pins.")
                .contains("Unknown kind: nonsense");
        assertThat(down.exit()).isEqualTo(1);
        assertThat(down.err()).contains("qits-maintenance cannot be reached or is not set up, so nothing ran "
                + "(HTTP 503). Try again later.");
        assertThat(busy.out() + unknown.out() + down.out()).isEmpty();
    }

    // --- dispatching work, and the delivery desk ---

    private static final String DISPATCH = "/projects/api/work/qits-100/dispatch";

    private void answerDispatch() {
        platform.answer("POST", DISPATCH, """
                {"dispatch":{"entityId":"e1","archetype":"TICKET","phase":"implement","mode":"FLOW","agentId":"agent-41",
                  "workspaceRowId":41,"repositoryId":"r1","branch":"ticket/qits-100","fresh":true,
                  "agentLaunch":"SCHEDULED","assignee":"ws-agent-41"}}
                """);
    }

    @Test
    void dispatchSendsTheModeAndTheRuleAndPrintsWhatItStarted() throws Exception {
        answerDispatch();

        Result r = run("work", "--entity", "qits-100", "dispatch", "--mode", "flow", "--rule", "backlog-newest-first");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DISPATCH).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mode\":\"FLOW\",\"rule\":\"backlog-newest-first\"}"));
        assertThat(r.out().lines().toList()).containsExactly(
                "ENTITY    MODE  PHASE      BRANCH           ASSIGNEE",
                "qits-100  FLOW  implement  ticket/qits-100  ws-agent-41");
    }

    @Test
    void dispatchWithoutARuleLeavesItOutAndJsonIsTheAnswerUnchanged() throws Exception {
        answerDispatch();

        Result r = run("work", "dispatch", "--entity", "qits-100", "--mode", "PHASE", "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(JSON.readTree(platform.requests("POST", DISPATCH).getFirst().body()))
                .isEqualTo(JSON.readTree("{\"mode\":\"PHASE\"}"));
        assertThat(JSON.readTree(r.out()).path("dispatch").path("assignee").asText()).isEqualTo("ws-agent-41");
    }

    @Test
    void dispatchNeedsAModeItKnowsAndSendsNothingWithout() {
        Result none = run("work", "--entity", "qits-100", "dispatch");
        Result wrong = run("work", "--entity", "qits-100", "dispatch", "--mode", "all");

        assertThat(none.exit()).isEqualTo(2);
        assertThat(none.err()).contains("Name the mode: --mode <FLOW or PHASE>.");
        assertThat(wrong.exit()).isEqualTo(2);
        assertThat(wrong.err()).contains("--mode is FLOW or PHASE, not all.");
        assertThat(platform.requests).isEmpty();
    }

    @Test
    void aDispatchRefusalReachesStderrInTheServicesWords() {
        String noPhase = "qits-100 is DONE, and DONE starts no phase.";
        platform.answer("POST", DISPATCH, 409, "{\"message\":\"" + noPhase + "\"}");
        Result conflict = run("work", "--entity", "qits-100", "dispatch", "--mode", "FLOW");
        String otherProject = "The delivery desk of demo may not dispatch qits-100 of another project.";
        platform.answer("POST", DISPATCH, 403, "{\"message\":\"" + otherProject + "\"}");
        Result forbidden = run("work", "--entity", "qits-100", "dispatch", "--mode", "FLOW", "--rule", "start-refined");

        assertThat(conflict.exit()).isEqualTo(1);
        assertThat(conflict.err()).contains("POST " + platform.url() + DISPATCH + " answered HTTP 409: " + noPhase);
        assertThat(forbidden.exit()).isEqualTo(1);
        assertThat(forbidden.err()).contains(otherProject);
        assertThat(conflict.out() + forbidden.out()).isEmpty();
    }

    @Test
    void deliveryPrintsTheProjectsSettings() throws Exception {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","delivery":{"enabled":true,
                  "maxRunning":4,"startRefined":true,"approves":["estate-pins","screenshots"],"interval":"PT5M"}}}]}
                """.formatted(QITS));

        Result r = run("projects", "delivery", "--project", "qits");

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out().lines().toList()).containsExactly(
                "Project qits: delivery desk",
                "SETTING        VALUE",
                "enabled        true",
                "max_running    4",
                "start_refined  true",
                "approves       estate-pins, screenshots",
                "interval       PT5M");
        assertThat(JSON.readTree(run("projects", "-o", "json", "delivery", "--project", QITS).out()))
                .isEqualTo(JSON.readTree("{\"enabled\":true,\"maxRunning\":4,\"startRefined\":true,"
                        + "\"approves\":[\"estate-pins\",\"screenshots\"],\"interval\":\"PT5M\"}"));
    }

    @Test
    void deliveryPrintsTheDefaultsForWhatTheServiceDoesNotSend() {
        platform.answer("GET", "/projects/api/projects", """
                {"entries":[{"project":{"id":"%s","name":"qits platform","slug":"qits","delivery":{"enabled":true,
                  "approves":[]}}},
                  {"project":{"id":"%s","name":"Demo","slug":"demo"}}]}
                """.formatted(QITS, DEMO));

        Result partly = run("projects", "delivery", "--project", "qits");
        Result older = run("projects", "delivery", "--project", "Demo");

        assertThat(partly.out().lines().toList()).containsExactly(
                "Project qits: delivery desk",
                "SETTING        VALUE",
                "enabled        true",
                "max_running    2 (default)",
                "start_refined  false (default)",
                "approves       none",
                "interval       PT10M (default)");
        assertThat(older.exit()).isZero();
        assertThat(older.out().lines().toList()).containsExactly(
                "Project demo: delivery desk",
                "SETTING        VALUE",
                "enabled        false (default)",
                "max_running    2 (default)",
                "start_refined  false (default)",
                "approves       none (default)",
                "interval       PT10M (default)");
        assertThat(run("projects", "delivery").err()).contains("Name the project: --project <id, slug or name>.");
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

    // --- repository git reads ---

    private static final String REPO = "/projects/api/repositories/" + CI;

    @Test
    void branchesAndCommitsResolveTheRepositoryByNameAndPassTheBranch() {
        platform.answer("GET", REPO + "/branches", """
                {"branches":[{"name":"ticket/x","parent":"main","ahead":2,"behind":0,"canCleanup":false}]}
                """);
        platform.answer("GET", REPO + "/commits", """
                {"branch":"ticket/x","parent":"main","commits":[{"hash":"abc","shortHash":"abc1234",
                  "author":"wohlben","date":"2026-10-10","message":"feat(qits-1): one\\n\\nbody","files":["a","b"]}]}
                """);

        Result branches = run("repositories", "--project", "qits", "branches", "--repository", "qits-ci-service");
        Result commits = run("repositories", "commits", "--project", "qits", "--repository", "qits-ci-service",
                "--branch", "ticket/x");

        assertThat(branches.exit()).as(branches.err()).isZero();
        assertThat(branches.out()).contains("ticket/x").contains("main");
        assertThat(commits.exit()).as(commits.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits").getFirst().query()).isEqualTo("branch=ticket%2Fx");
        assertThat(commits.out()).contains("abc1234").contains("feat(qits-1): one").doesNotContain("body");
    }

    @Test
    void commitChangesDiffAndSubjectsSendTheirQueries() {
        platform.answer("GET", REPO + "/commits/abc/changes", """
                {"commit":"abc","files":[{"path":"README.md","changeType":"MODIFY"}]}
                """);
        platform.answer("GET", REPO + "/commits/abc/diff", """
                {"path":"README.md","changeType":"MODIFY","diff":"@@ -1 +1 @@\\n-a\\n+b"}
                """);
        platform.answer("GET", REPO + "/commit-subjects", """
                {"branch":"main","guardEnabled":true,"counts":{"total":3,"complying":2,"nonComplying":1,"exempt":0},
                 "nonComplying":[{"shortHash":"def5678","authorName":"x","subject":"fix stuff"}]}
                """);

        Result changes = run("repositories", "--project", "qits", "commit-changes", "--repository", CI,
                "--commit", "abc", "--parent", "p0");
        Result diff = run("repositories", "--project", "qits", "commit-diff", "--repository", CI, "--commit", "abc",
                "--path", "README.md");
        Result subjects = run("repositories", "--project", "qits", "commit-subjects", "--repository", CI,
                "--limit", "20");

        assertThat(changes.exit()).as(changes.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits/abc/changes").getFirst().query()).isEqualTo("parent=p0");
        assertThat(changes.out()).contains("MODIFY").contains("README.md");
        assertThat(diff.exit()).as(diff.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commits/abc/diff").getFirst().query()).isEqualTo("path=README.md");
        assertThat(diff.out()).contains("+b");
        assertThat(subjects.exit()).as(subjects.err()).isZero();
        assertThat(platform.requests("GET", REPO + "/commit-subjects").getFirst().query()).isEqualTo("limit=20");
        assertThat(subjects.out()).contains("2 complying, 1 not").contains("guard on").contains("fix stuff");
    }

    @Test
    void aGitReadNeedsTheRepository() {
        Result r = run("repositories", "--project", "qits", "branches");

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--repository");
    }
}
