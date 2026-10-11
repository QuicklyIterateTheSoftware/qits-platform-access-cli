package eu.wohlben.qits.cli.access.publish;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code qits artifacts publish changelog} end to end: the payload, qits-projects and qits-ci
 * stubbed on one server beside the store, and the bytes that reach the docs PUT read back out of the
 * archive.
 */
class ChangelogPublishTest {

    private static final String VERSION = "2026.1012.91502";
    private static final String PUT = "/artifacts/docs/docs/@changelog/qits-ci/-/" + VERSION;
    private static final String COMMITS = "/projects/api/repositories/repo-1/release-requests/rr-1/commits";
    private static final String GATE = "/ci/api/runs/run-1/gate/reports";
    private static final String PAYLOAD = "{\"eventId\":\"e-1\",\"projectId\":\"p-1\",\"repository\":\"repo-1\","
            + "\"repositoryName\":\"qits-ci\",\"branch\":\"main\",\"version\":\"" + VERSION + "\","
            + "\"commitSha\":\"cafe\",\"releaseRequestId\":\"rr-1\",\"occurredAt\":\"2026-10-12T09:15:02.123456Z\","
            + "\"priority\":\"NORMAL\"}";

    private static final String THREE_COMMITS = """
            {"mergedSha":"m","commits":[
              {"hash":"m","shortHash":"mmmmmmm","author":"qits","message":"Release request rr-1: fold","fold":true},
              {"hash":"c","shortHash":"ccc3333","author":"Bob","message":"fix(qits-10, qits-9): third\\n\\nbody","fold":false},
              {"hash":"b","shortHash":"bbb2222","author":"Alice","message":"chore(deps): second","fold":false},
              {"hash":"a","shortHash":"aaa1111","author":"Alice","message":"feat(qits-404): first","fold":false}
            ],"detail":null,"foldParents":[],"sources":[]}""";

    private StubStore stub;
    private Harness cli;

    @BeforeEach
    void setUp() {
        stub = new StubStore();
        cli = new Harness().store(stub).platform(stub)
                .with("QITS_EVENT_PAYLOAD", PAYLOAD)
                .with("QITS_CI_RUN_ID", "run-1")
                .with("QITS_PUBLISH_TOKEN", "token")
                .with("QITS_DOMAIN", "example.org");
        stub.on("PUT", PUT, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":100}"));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private Harness.Run publish() {
        return cli.run("changelog", "--version", VERSION, "--meta", "git.commit.hash=cafe",
                "--meta", "git.repository.name=qits-ci");
    }

    private String uploaded() {
        StubStore.Request put = stub.requests().stream()
                .filter(r -> r.method().equals("PUT") && r.path().equals(PUT)).findFirst().orElseThrow();
        List<Archives.Entry> entries = Archives.readTarGz(put.body(), "the uploaded bundle");
        assertThat(entries).extracting(Archives.Entry::name).containsExactly("CHANGELOG.md");
        return new String(entries.get(0).bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aReleaseIsPublishedWithItsCommitsTicketsAndHighlights() {
        stub.on("GET", COMMITS, json(THREE_COMMITS));
        stub.on("GET", "/projects/api/work/qits-9", json("{\"qualifiedId\":\"qits-9\",\"title\":\"Nine\"}"));
        stub.on("GET", "/projects/api/work/qits-10", json("{\"qualifiedId\":\"qits-10\",\"title\":\"Ten\"}"));
        stub.on("GET", GATE, json("""
                {"runId":"run-1","commitSha":"c","releaseRequestId":"rr-1","baseline":null,"reports":[
                  {"id":"r3","kind":"test-results","kindVersion":1,"stepIndex":1,
                   "highlights":[{"severity":"WARN","text":"3 failed","metric":"failed","value":3,"delta":1}]},
                  {"id":"r2","kind":"coverage","kindVersion":1,"stepIndex":1,
                   "highlights":[{"severity":"INFO","text":"80.9% covered"}]},
                  {"id":"r1","kind":"contracts","kindVersion":1,"stepIndex":0,
                   "highlights":[{"severity":"INFO","text":"2 pacts"},{"severity":"WARN","text":"1 removed"}]}
                ]}"""));

        Harness.Run run = publish();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).isEqualTo("""
                # qits-ci 2026.1012.91502

                Release request `rr-1`, released 2026-10-12T09:15:02Z.

                ## Commits

                - `aaa1111` feat(qits-404): first (Alice)
                - `bbb2222` chore(deps): second (Alice)
                - `ccc3333` fix(qits-10, qits-9): third (Bob)

                ## Tickets

                - [`qits-9`](https://qits.example.org/projects/qits/work/detail/qits-9) Nine
                - [`qits-10`](https://qits.example.org/projects/qits/work/detail/qits-10) Ten
                - `qits-404` (no such work item)

                ## Report highlights

                - **contracts** INFO: 2 pacts
                - **contracts** WARN: 1 removed
                - **coverage** INFO: 80.9% covered
                - **test-results** WARN: 3 failed

                associated tickets: `qits-9` `qits-10`
                """);
        StubStore.Request put = stub.requests().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.header("X-Artifacts-Meta-git.commit.hash")).isEqualTo("cafe");
        assertThat(put.header("X-Artifacts-Meta-git.repository.name")).isEqualTo("qits-ci");
        assertThat(put.header("X-Artifacts-Meta-release.request.id")).isEqualTo("rr-1");
        assertThat(put.header("X-Artifacts-Meta-release.request.qualified-id")).isNull();
        assertThat(put.header("Authorization")).isEqualTo("Bearer token");
        assertThat(stub.lastRequest().path()).isEqualTo(PUT);
        StubStore.Request commits = stub.requests().stream().filter(r -> r.path().equals(COMMITS)).findFirst()
                .orElseThrow();
        assertThat(commits.header("Authorization")).isEqualTo("Bearer token");
    }

    @Test
    void aReleaseWithNoTicketsSaysNoneAndEndsInABareLine() {
        stub.on("GET", COMMITS, json("""
                {"mergedSha":"m","commits":[
                  {"hash":"b","shortHash":"bbb2222","author":"Alice","message":"bump(targeted): 2 dependencies"}
                ]}"""));
        stub.on("GET", GATE, json("{\"reports\":[]}"));

        Harness.Run run = publish();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).isEqualTo("""
                # qits-ci 2026.1012.91502

                Release request `rr-1`, released 2026-10-12T09:15:02Z.

                ## Commits

                - `bbb2222` bump(targeted): 2 dependencies (Alice)

                ## Tickets

                None.

                associated tickets:
                """);
    }

    @Test
    void anUnknownTicketIsListedWithoutALinkAndLeftOutOfTheLastLine() {
        stub.on("GET", COMMITS, json("""
                {"commits":[{"hash":"a","shortHash":"aaa1111","author":"Alice","message":"feat(nope-1): gone"}]}"""));

        Harness.Run run = publish();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).contains("- `nope-1` (no such work item)\n").endsWith("\n\nassociated tickets:\n");
    }

    @Test
    void noGateReportsDoorLeavesTheHighlightsOut() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));

        Harness.Run run = publish();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(stub.paths()).contains("GET " + GATE);
        assertThat(uploaded()).doesNotContain("## Report highlights").contains("## Commits\n\nNone.\n");
    }

    @Test
    void aProjectsFailureIsCouldNotAskAndPublishesNothing() {
        stub.on("GET", COMMITS, StubStore.Reply.of(503, "down"));

        Harness.Run run = publish();

        assertThat(run.code()).isEqualTo(ExitCode.TRANSPORT);
        assertThat(run.err()).contains("503");
        assertThat(stub.paths()).doesNotContain("PUT " + PUT);
    }

    @Test
    void anUnknownReleaseRequestIsRefused() {
        Harness.Run run = publish();

        assertThat(run.code()).isEqualTo(ExitCode.POLICY);
        assertThat(stub.paths()).doesNotContain("PUT " + PUT);
    }

    @Test
    void aGateFailureIsCouldNotAsk() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        stub.on("GET", GATE, StubStore.Reply.of(500, "boom"));

        assertThat(publish().code()).isEqualTo(ExitCode.TRANSPORT);
        assertThat(stub.paths()).doesNotContain("PUT " + PUT);
    }

    @Test
    void aTicketReadFailureOtherThan404IsCouldNotAsk() {
        stub.on("GET", COMMITS, json("{\"commits\":[{\"shortHash\":\"a\",\"message\":\"feat(qits-1): x\"}]}"));
        stub.on("GET", "/projects/api/work/qits-1", StubStore.Reply.of(502, "bad gateway"));

        assertThat(publish().code()).isEqualTo(ExitCode.TRANSPORT);
    }

    @Test
    void anOccupiedVersionIsSkippedAndSucceeds() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        stub.on("PUT", PUT, StubStore.Reply.of(409, "docs versions are immutable"));

        Harness.Run run = publish();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(run.err()).contains("already published");
    }

    @Test
    void aPayloadWithoutTheRequestNamesTheField() {
        Harness.Run run = cli.with("QITS_EVENT_PAYLOAD", "{\"repository\":\"repo-1\",\"repositoryName\":\"qits-ci\"}")
                .run("changelog", "--version", VERSION);

        assertThat(run.code()).isEqualTo(ExitCode.POLICY);
        assertThat(run.err()).contains("releaseRequestId");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void noPayloadOrNoRunIsRefused() {
        assertThat(cli.with("QITS_EVENT_PAYLOAD", "").run("changelog", "--version", VERSION).err())
                .contains("QITS_EVENT_PAYLOAD");
        Harness.Run noRun = new Harness().store(stub).platform(stub).with("QITS_EVENT_PAYLOAD", PAYLOAD)
                .run("changelog", "--version", VERSION);
        assertThat(noRun.code()).isEqualTo(ExitCode.POLICY);
        assertThat(noRun.err()).contains("QITS_CI_RUN_ID");
    }

    @Test
    void noTimeInThePayloadLeavesTheTimeOut() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        Harness.Run run = cli.with("QITS_EVENT_PAYLOAD",
                        "{\"repository\":\"repo-1\",\"repositoryName\":\"qits-ci\",\"releaseRequestId\":\"rr-1\"}")
                .run("changelog", "--version", VERSION);

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).contains("\n\nRelease request `rr-1`.\n\n");
    }

    @Test
    void aLogicalIdNamesTheRequestAndIsAddedToTheMetadata() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        Harness.Run run = cli.with("QITS_EVENT_PAYLOAD", "{\"repository\":\"repo-1\",\"repositoryName\":\"qits-ci\","
                        + "\"releaseRequestId\":\"rr-1\",\"releaseRequestQualifiedId\":\"qits-ci-rr-12\"}")
                .run("changelog", "--version", VERSION);

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).contains("\n\nRelease request `qits-ci-rr-12`.\n\n");
        StubStore.Request put = stub.requests().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.header("X-Artifacts-Meta-release.request.id")).isEqualTo("rr-1");
        assertThat(put.header("X-Artifacts-Meta-release.request.qualified-id")).isEqualTo("qits-ci-rr-12");
        assertThat(stub.requests()).anyMatch(r -> r.path().equals(COMMITS));
    }

    @Test
    void noTimeInThePayloadOrTheEnvLeavesTheTimeOut() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        Harness.Run run = cli.with("QITS_EVENT_PAYLOAD",
                        "{\"repository\":\"repo-1\",\"repositoryName\":\"qits-ci\",\"releaseRequestId\":\"rr-1\"}")
                .with("QITS_EVENT_OCCURRED_AT", "not a time")
                .run("changelog", "--version", VERSION);

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).contains("\n\nRelease request `rr-1`.\n\n");
    }

    @Test
    void noTimeInThePayloadFallsBackToTheEnvVariable() {
        stub.on("GET", COMMITS, json("{\"commits\":[]}"));
        Harness.Run run = cli.with("QITS_EVENT_PAYLOAD",
                        "{\"repository\":\"repo-1\",\"repositoryName\":\"qits-ci\",\"releaseRequestId\":\"rr-1\"}")
                .with("QITS_EVENT_OCCURRED_AT", "2026-07-31T12:46:03Z")
                .run("changelog", "--version", VERSION);

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploaded()).contains("\n\nRelease request `rr-1`, released 2026-07-31T12:46:03Z.\n\n");
    }

    // --- qits-1193: the commits of earlier releases that published no changelog ------------------

    /** qits-workspace-daemon as it stood when rr-102 published 2026.1011.134206 (measured 2026-10-11). */
    private static final String WSD = "0b6cbfde-9945-4d78-b980-1dac2e1e1a7a";
    private static final String WSD_VERSION = "2026.1011.134206";
    private static final String WSD_PUT = "/artifacts/docs/docs/@changelog/qits-workspace-daemon/-/" + WSD_VERSION;
    private static final String WSD_LISTING = "/artifacts/docs/docs/@changelog/qits-workspace-daemon";
    private static final String WSD_HISTORY = "/projects/api/repositories/" + WSD + "/release-requests";
    private static final String RR_100 = "0220b318-b14e-4004-a0f4-39a1c693c7ca";
    private static final String RR_101 = "a9c909c2-318d-41af-ad31-78e2e3d5adfd";
    private static final String RR_102 = "f361a0ca-7806-4521-b50a-9f5a84e34e5b";
    private static final String WSD_PAYLOAD = "{\"repository\":\"" + WSD + "\",\"repositoryName\":\"qits-workspace-daemon\","
            + "\"releaseRequestId\":\"" + RR_102 + "\",\"releaseRequestQualifiedId\":\"qits-workspace-daemon-rr-102\","
            + "\"occurredAt\":\"2026-10-11T13:42:06Z\"}";

    /** The listing before 2026.1011.134206 published: 2026.1011.81850 and 2026.1011.115738 never did. */
    private static final String WSD_PUBLISHED = """
            {"name":"@changelog/qits-workspace-daemon","versions":[
              {"version":"2026.1010.203838"},{"version":"2026.1010.154553"},
              {"version":"2026.1010.141711"},{"version":"2026.1010.134256"}]}""";

    /** Newest first, as qits-projects answers; rr-104 is a later release, for a rerun of this step. */
    private static final String WSD_REQUESTS = """
            {"requests":[
              {"id":"1e387f4b-e142-4702-9af1-262bf4cce3a5","qualifiedId":"qits-workspace-daemon-rr-104","state":"FINALIZED","version":"2026.1011.142100"},
              {"id":"d435cdde-a0c2-4ed4-87f9-5ca78becba50","qualifiedId":"qits-workspace-daemon-rr-103","state":"WITHDRAWN","version":null},
              {"id":"%s","qualifiedId":"qits-workspace-daemon-rr-102","state":"RELEASED","version":"2026.1011.134206"},
              {"id":"%s","qualifiedId":"qits-workspace-daemon-rr-101","state":"OBSOLETE","version":"2026.1011.115738"},
              {"id":"%s","qualifiedId":"qits-workspace-daemon-rr-100","state":"OBSOLETE","version":"2026.1011.81850"},
              {"id":"6a1e0c7e-0000-4000-8000-000000000098","qualifiedId":"qits-workspace-daemon-rr-98","state":"FINALIZED","version":"2026.1010.203838"}
            ]}""".formatted(RR_102, RR_101, RR_100);

    private Harness.Run publishWorkspaceDaemon() {
        stub.on("PUT", WSD_PUT, StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":100}"));
        stub.on("GET", WSD_HISTORY + "/" + RR_100 + "/commits", json("""
                {"commits":[
                  {"hash":"cab706e9da86988ddcb21b1aed4b87ac5072d946","shortHash":"cab706e","author":"qits maintenance",
                   "message":"chore(qits-1174): bump(qits-1174): 2 dependencies","fold":false},
                  {"hash":"4ba03696a1cf","shortHash":"4ba0369","author":"wohlben",
                   "message":"chore(qits-1174): attach only the central qits MCP server and the browser","fold":false}]}"""));
        stub.on("GET", WSD_HISTORY + "/" + RR_101 + "/commits", json("""
                {"commits":[
                  {"hash":"285886bf249ff129aa2ca94f2b1584997b952935","shortHash":"285886b","author":"wohlben",
                   "message":"chore(qits-1174): drop the retired-server filter now that qits-coding-agents skips those servers itself","fold":false}]}"""));
        stub.on("GET", WSD_HISTORY + "/" + RR_102 + "/commits", json("""
                {"commits":[
                  {"hash":"05b35f4c23a8","shortHash":"05b35f4","author":"qits-projects",
                   "message":"Release request qits-workspace-daemon-rr-102: qits-1174","fold":true},
                  {"hash":"6d19d76620ff","shortHash":"6d19d76","author":"wohlben",
                   "message":"docs(qits-1174): say why a rerun release step cannot publish the daemon jar again","fold":false}]}"""));
        stub.on("GET", "/projects/api/work/qits-1174", json("{\"qualifiedId\":\"qits-1174\",\"title\":\"Central MCP\"}"));
        return cli.with("QITS_EVENT_PAYLOAD", WSD_PAYLOAD).run("changelog", "--version", WSD_VERSION);
    }

    private String uploadedTo(String path) {
        StubStore.Request put = stub.requests().stream()
                .filter(r -> r.method().equals("PUT") && r.path().equals(path)).findFirst().orElseThrow();
        List<Archives.Entry> entries = Archives.readTarGz(put.body(), "the uploaded bundle");
        return new String(entries.get(0).bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theCommitsOfEveryEarlierReleaseSinceTheLastPublishedChangelogAreListed() {
        stub.on("GET", WSD_LISTING, json(WSD_PUBLISHED));
        stub.on("GET", WSD_HISTORY, json(WSD_REQUESTS));

        Harness.Run run = publishWorkspaceDaemon();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploadedTo(WSD_PUT)).isEqualTo("""
                # qits-workspace-daemon 2026.1011.134206

                Release request `qits-workspace-daemon-rr-102`, released 2026-10-11T13:42:06Z.

                ## Commits

                With the commits of 2026.1011.81850 (`qits-workspace-daemon-rr-100`) and 2026.1011.115738 (`qits-workspace-daemon-rr-101`), which published no changelog.

                - `4ba0369` chore(qits-1174): attach only the central qits MCP server and the browser (wohlben)
                - `cab706e` chore(qits-1174): bump(qits-1174): 2 dependencies (qits maintenance)
                - `285886b` chore(qits-1174): drop the retired-server filter now that qits-coding-agents skips those servers itself (wohlben)
                - `6d19d76` docs(qits-1174): say why a rerun release step cannot publish the daemon jar again (wohlben)

                ## Tickets

                - [`qits-1174`](https://qits.example.org/projects/qits/work/detail/qits-1174) Central MCP

                associated tickets: `qits-1174`
                """);
        // rr-98 published, rr-104 is later: neither one's commits are asked for.
        assertThat(stub.paths()).noneMatch(p -> p.contains("6a1e0c7e") || p.contains("1e387f4b"));
    }

    @Test
    void aRepositoryWithNoPublishedChangelogCarriesNoEarlierRelease() {
        Harness.Run run = publishWorkspaceDaemon();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploadedTo(WSD_PUT)).doesNotContain("With the commits of").doesNotContain("4ba0369")
                .contains("- `6d19d76` docs(qits-1174)");
        assertThat(stub.paths()).doesNotContain("GET " + WSD_HISTORY);
    }

    @Test
    void aPublishedPreviousReleaseCarriesNothingEarlier() {
        stub.on("GET", WSD_LISTING, json("""
                {"versions":[{"version":"2026.1011.115738"},{"version":"2026.1010.203838"}]}"""));
        stub.on("GET", WSD_HISTORY, json(WSD_REQUESTS));

        Harness.Run run = publishWorkspaceDaemon();

        assertThat(run.code()).as(run.err()).isEqualTo(ExitCode.OK);
        assertThat(uploadedTo(WSD_PUT)).doesNotContain("With the commits of").doesNotContain("285886b");
        assertThat(stub.paths()).noneMatch(p -> p.contains(RR_100) || p.contains(RR_101));
    }

    @Test
    void anUnreadableListingIsCouldNotAskAndPublishesNothing() {
        stub.on("GET", WSD_LISTING, StubStore.Reply.of(503, "down"));

        Harness.Run run = publishWorkspaceDaemon();

        assertThat(run.code()).isEqualTo(ExitCode.TRANSPORT);
        assertThat(run.err()).contains("changelog listing").contains("503");
        assertThat(stub.paths()).doesNotContain("PUT " + WSD_PUT);
    }

    @Test
    void anUnreadableHistoryIsCouldNotAskAndPublishesNothing() {
        stub.on("GET", WSD_LISTING, json(WSD_PUBLISHED));
        stub.on("GET", WSD_HISTORY, StubStore.Reply.of(502, "bad gateway"));

        Harness.Run run = publishWorkspaceDaemon();

        assertThat(run.code()).isEqualTo(ExitCode.TRANSPORT);
        assertThat(stub.paths()).doesNotContain("PUT " + WSD_PUT);
    }

    private static StubStore.Reply json(String body) {
        return StubStore.Reply.of(200, body, java.util.Map.of("Content-Type", "application/json"));
    }
}
