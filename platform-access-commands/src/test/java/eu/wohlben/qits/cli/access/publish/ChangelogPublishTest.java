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

    private static StubStore.Reply json(String body) {
        return StubStore.Reply.of(200, body, java.util.Map.of("Content-Type", "application/json"));
    }
}
