package eu.wohlben.qits.cli.access.agents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
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

import static org.assertj.core.api.Assertions.assertThat;

/** The hook as Claude runs it: an event on stdin, the text to show or nothing on stdout, always exit 0. */
class WorkLinksCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-10T10:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DETAIL = "https://qits.wohlben.eu/projects/qits/work/detail/";

    @TempDir
    Path home;

    private final FakeTime time = new FakeTime(T0);
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void setUp() {
        env.put("XDG_CONFIG_HOME", home.resolve("config").toString());
        env.put("XDG_CACHE_HOME", home.resolve("cache").toString());
        env.put("XDG_RUNTIME_DIR", home.resolve("run").toString());
        env.put("QITS_LANDING_URL", "https://qits.wohlben.eu/");
        env.put("QITS_LINK_PROJECTS", "qits");
    }

    private Result run(String stdin, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        String[] argv = new String[args.length + 4];
        System.arraycopy(new String[]{"agents", "claude", "hook", "work-links"}, 0, argv, 0, 4);
        System.arraycopy(args, 0, argv, 4, args.length);
        int exit = TestCli.execute(context, argv);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private static String event(String messageId, int index, boolean last, String delta) {
        return JSON.createObjectNode().put("hook_event_name", "MessageDisplay").put("message_id", messageId)
                .put("index", index).put("final", last).put("delta", delta).toString();
    }

    private static String displayed(Result r) throws Exception {
        JsonNode output = JSON.readTree(r.out()).path("hookSpecificOutput");
        assertThat(output.path("hookEventName").asText()).isEqualTo("MessageDisplay");
        return output.path("displayContent").asText();
    }

    @Test
    void anIdIsShownAsALink() throws Exception {
        Result r = run(event("m1", 0, true, "See qits-1152 and ticket/qits-1152, `qits-5`.\n"));

        assertThat(r.exit()).isZero();
        assertThat(displayed(r)).isEqualTo("See [qits-1152](" + DETAIL + "qits-1152) and ticket/qits-1152, `qits-5`.\n");
    }

    @Test
    void nothingLinkedPrintsNothing() {
        assertThat(run(event("m1", 0, true, "No ids here, only utf-8.\n"))).isEqualTo(new Result(0, "", ""));
        assertThat(run(event("m1", 0, true, ""))).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void anythingMalformedPrintsNothingAndExitsZero() {
        for (String stdin : List.of("", "not json", "[1,2]", "{\"delta\": 5}", "null")) {
            assertThat(run(stdin)).as(stdin).isEqualTo(new Result(0, "", ""));
        }
    }

    @Test
    void noLandingAddressPrintsNothing() {
        env.remove("QITS_LANDING_URL");
        assertThat(run(event("m1", 0, true, "qits-1\n"))).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void aFenceOpenInOneBatchHoldsInTheNext() throws Exception {
        Result first = run(event("m2", 0, false, "```\nqits-1\n"));
        assertThat(first.out()).isEmpty();
        Path state = home.resolve("run/qits-work-links/m2");
        assertThat(state).exists();

        assertThat(run(event("m2", 1, false, "qits-2\n```\n")).out()).isEmpty();
        assertThat(state).doesNotExist();

        assertThat(displayed(run(event("m2", 2, true, "qits-3\n")))).isEqualTo("[qits-3](" + DETAIL + "qits-3)\n");
    }

    @Test
    void theLastBatchRemovesTheState() {
        run(event("m3", 0, false, "```\n"));
        run(event("m3", 1, true, "qits-1\n"));
        assertThat(home.resolve("run/qits-work-links/m3")).doesNotExist();
    }

    @Test
    void osc8ByOptionOrVariable() throws Exception {
        String link = "\u001b]8;;" + DETAIL + "qits-9\u001b\\qits-9\u001b]8;;\u001b\\";
        assertThat(displayed(run(event("m4", 0, true, "qits-9"), "--style", "osc8"))).isEqualTo(link);
        env.put("QITS_LINK_STYLE", "osc8");
        assertThat(displayed(run(event("m4", 0, true, "qits-9")))).isEqualTo(link);
    }

    /** On a workstation the landing app comes from the session file, read without a refresh. */
    @Test
    void theLandingAppComesFromTheSessionsIdp() throws Exception {
        env.remove("QITS_LANDING_URL");
        new SessionFile(home.resolve("config/qits")).write(new Session("https://idp.qits.example.org/idp", "qits-cli",
                "access", T0.minusSeconds(3600), "refresh", T0.minusSeconds(60)));

        assertThat(displayed(run(event("m5", 0, true, "qits-1"))))
                .isEqualTo("[qits-1](https://qits.example.org/projects/qits/work/detail/qits-1)");
    }

    @Test
    void theCachedSlugsAreUsed() throws Exception {
        env.remove("QITS_LINK_PROJECTS");
        LinkProjects.fromEnvironment(env, time).write(List.of("shop"));

        assertThat(displayed(run(event("m6", 0, true, "shop-4 qits-4"))))
                .isEqualTo("[shop-4](https://qits.wohlben.eu/projects/shop/work/detail/shop-4) qits-4");
    }

    @Test
    void theRefreshWritesTheSlugsFromTheProjectsService() throws Exception {
        try (FakePlatform platform = new FakePlatform()) {
            platform.answer("GET", "/projects/api/projects",
                    "{\"entries\":[{\"project\":{\"slug\":\"qits\"}},{\"project\":{\"slug\":\"shop\"}},{\"project\":{}}]}");
            env.remove("QITS_LINK_PROJECTS");
            env.put(Mode.TOKEN, "the workspace token");
            env.put("QITS_DOMAIN", "wohlben.eu");
            env.put("QITS_PROJECTS_URL", platform.url());

            Result r = run("", "--refresh");

            assertThat(r.exit()).as(r.err()).isZero();
            assertThat(r.out()).isEmpty();
            Path cache = home.resolve("cache/qits/work-links.json");
            assertThat(LinkProjects.slugs(JSON.readTree(Files.readString(cache)))).containsExactly("qits", "shop");
        }
    }
}
