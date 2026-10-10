package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.session.Mode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static eu.wohlben.qits.cli.access.agents.TranscriptIdsTest.assistant;
import static eu.wohlben.qits.cli.access.agents.TranscriptIdsTest.toolResult;
import static org.assertj.core.api.Assertions.assertThat;

/** The status line as Claude runs it: its JSON on stdin, one line or nothing on stdout, always exit 0. */
class WorkItemsCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final FakeTime time = new FakeTime(T0);
    private final Map<String, String> env = new HashMap<>();
    private Path transcript;

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void setUp() {
        env.put("XDG_CONFIG_HOME", home.resolve("config").toString());
        env.put("XDG_CACHE_HOME", home.resolve("cache").toString());
        env.put("QITS_LANDING_URL", "https://qits.wohlben.eu");
        env.put("QITS_LINK_PROJECTS", "qits");
        transcript = home.resolve("t.jsonl");
    }

    private Result run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        String[] argv = new String[args.length + 4];
        System.arraycopy(new String[]{"agents", "claude", "statusline", "work-items"}, 0, argv, 0, 4);
        System.arraycopy(args, 0, argv, 4, args.length);
        int exit = TestCli.execute(context, argv);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private String session() {
        return JSON.createObjectNode().put("session_id", "s").put("transcript_path", transcript.toString()).toString();
    }

    private static String osc8(String id) {
        return "\u001b]8;;https://qits.wohlben.eu/projects/qits/work/detail/" + id + "\u001b\\" + id + "\u001b]8;;\u001b\\";
    }

    @Test
    void theCachedStatesShowInFrontOfTheLinks() throws Exception {
        Files.writeString(transcript, String.join("\n", assistant("qits-1158"), toolResult("qits-1168"),
                assistant("qits-1153 qits-9")) + "\n");
        WorkStates.fromEnvironment(env, time).write(Map.of(
                "qits-1153", new WorkStates.State("IMPLEMENTING", true, "EXPLICIT"),
                "qits-1168", new WorkStates.State("IMPLEMENTING", false, null),
                "qits-1158", new WorkStates.State("VERIFIED", false, null)));

        Result r = run(session());

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo(osc8("qits-9") + "  ❗🟦 " + osc8("qits-1153") + "  🟦 " + osc8("qits-1168")
                + "  ✅ " + osc8("qits-1158") + "\n");
    }

    @Test
    void anIdWithNothingCachedShowsWithoutAMarkAndIsClaimedForRefresh() throws Exception {
        Files.writeString(transcript, assistant("qits-5") + "\n");

        assertThat(run(session()).out()).isEqualTo(osc8("qits-5") + "\n");
        assertThat(WorkStates.fromEnvironment(env, time).claimStale(List.of("qits-5")))
                .as("the run claimed it already").isEmpty();
    }

    @Test
    void withoutALandingAddressTheIdsArePlain() throws Exception {
        env.remove("QITS_LANDING_URL");
        Files.writeString(transcript, assistant("qits-5") + "\n");

        assertThat(run(session()).out()).isEqualTo("qits-5\n");
    }

    @Test
    void anythingMissingOrMalformedPrintsNothingAndExitsZero() {
        for (String stdin : List.of("", "not json", "{}", "{\"transcript_path\": \"/no/such/file\"}")) {
            assertThat(run(stdin)).as(stdin).isEqualTo(new Result(0, "", ""));
        }
    }

    @Test
    void theRefreshWritesEachIdsState() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            platform.answer("GET", "/projects/api/work/qits-1",
                    "{\"qualifiedId\":\"qits-1\",\"status\":\"IMPLEMENTING\",\"blocked\":true,\"blockSource\":\"AGENT_WAITING\"}");
            platform.answer("GET", "/projects/api/work/qits-2", "{\"qualifiedId\":\"qits-2\",\"status\":\"DONE\",\"blocked\":false}");
            env.put(Mode.TOKEN, "the workspace token");
            env.put("QITS_DOMAIN", "wohlben.eu");
            env.put("QITS_PROJECTS_URL", platform.url());

            Result r = run("", "--refresh", "qits-1", "qits-2", "qits-3");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(WorkStates.fromEnvironment(env, time).read(List.of("qits-1", "qits-2", "qits-3"))).isEqualTo(Map.of(
                    "qits-1", new WorkStates.State("IMPLEMENTING", true, "AGENT_WAITING"),
                    "qits-2", new WorkStates.State("DONE", false, null)));
        }
    }
}
