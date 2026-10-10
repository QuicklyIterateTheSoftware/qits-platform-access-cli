package eu.wohlben.qits.cli.access.changelog;

import static org.assertj.core.api.Assertions.assertThat;

import eu.wohlben.qits.cli.access.AccessCli;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.platform.PlatformCommand;
import eu.wohlben.qits.cli.access.publish.StubStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code qits changelog bump-message} the way a bump step runs it: the payload in the environment,
 * the two files on disk, and the docs store stubbed. The expected messages are whole strings.
 */
class BumpMessageTest {

    private static final String DOCS = "/artifacts/docs/docs/@changelog/";
    private static final String BODY = "- maven eu.wohlben.qits:a 1 -> 2 (pom.xml)\n- npm @qits/b 3 -> 4 (package.json)\n\n\n";

    @TempDir
    Path dir;

    private StubStore store;
    private final Map<String, String> env = new LinkedHashMap<>();

    record Run(int code, String out, String err) {
    }

    @BeforeEach
    void setUp() {
        store = new StubStore();
        env.put("QITS_PUBLISH_TOKEN", "token");
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void changelog(String repository, String version, String text) {
        store.on("GET", DOCS + repository + "/-/" + version + "/CHANGELOG.md", StubStore.Reply.of(200, text));
    }

    private static String doc(String repository, String version, String tickets) {
        return "# " + repository + " " + version + "\n\nRelease request `rr`.\n\nassociated tickets:" + tickets + "\n";
    }

    private Run run(String payload, String applied) throws IOException {
        Path appliedFile = Files.writeString(dir.resolve("applied.tsv"), applied);
        Path bodyFile = Files.writeString(dir.resolve("body.txt"), BODY);
        if (payload != null) {
            env.put("QITS_EVENT_PAYLOAD", payload);
        }
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        int code;
        try (PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
             PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8)) {
            CliContext context = new CliContext(Map.copyOf(env), java.io.InputStream.nullInputStream(), out, err,
                    Clock.systemUTC(), Sleeper.real(), name -> null, runnable -> {
            });
            picocli.CommandLine cli = new picocli.CommandLine(new AccessCli(), new picocli.CommandLine.IFactory() {
                @Override
                public <K> K create(Class<K> type) throws Exception {
                    K made = picocli.CommandLine.defaultFactory().create(type);
                    if (made instanceof PlatformCommand command) {
                        command.useContext(context);
                    }
                    if (made instanceof BumpMessageCommand command) {
                        command.registryOrigin = store.url();
                    }
                    return made;
                }
            });
            cli.setOut(new PrintWriter(out, true, StandardCharsets.UTF_8));
            cli.setErr(new PrintWriter(err, true, StandardCharsets.UTF_8));
            code = cli.execute("changelog", "bump-message", "--group", "targeted", "--applied", appliedFile.toString(),
                    "--body", bodyFile.toString());
        }
        return new Run(code, outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    private static String change(String ecosystem, String name, String repository, String... versions) {
        String changelog = repository == null ? ""
                : ",\"changelog\":{\"repository\":\"" + repository + "\",\"versions\":[\""
                        + String.join("\",\"", versions) + "\"]}";
        return "{\"ecosystem\":\"" + ecosystem + "\",\"name\":\"" + name + "\",\"from\":\"1\",\"to\":\"2\"" + changelog + "}";
    }

    private static String payload(String... changes) {
        return "{\"eventId\":\"e\",\"changes\":[" + String.join(",", changes) + "]}";
    }

    @Test
    void repositoriesByNameVersionsInVersionOrderTicketsUnitedAndSorted() throws IOException {
        changelog("qits-ci", "2026.1009.5", doc("qits-ci", "2026.1009.5", " `qits-10`"));
        changelog("qits-ci", "2026.1010.1", doc("qits-ci", "2026.1010.1", ""));
        changelog("qits-ci", "2026.1010.20", doc("qits-ci", "2026.1010.20", " `qits-9` `qits-10`"));
        changelog("qits-artifacts", "2026.1001.1", doc("qits-artifacts", "2026.1001.1", " `abc-100`") + "\n\n");

        Run run = run(payload(
                        change("npm", "@qits/b", "qits-ci", "2026.1010.20", "2026.1009.5"),
                        change("maven", "eu.wohlben.qits:a", "qits-artifacts", "2026.1001.1"),
                        change("maven", "eu.wohlben.qits:c", "qits-ci", "2026.1010.1", "2026.1010.20")),
                "maven\teu.wohlben.qits:a\nnpm\t@qits/b\nmaven\teu.wohlben.qits:c\n");

        assertThat(run.code()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("""
                chore(abc-100, qits-9, qits-10): bump(targeted): 3 dependencies

                - maven eu.wohlben.qits:a 1 -> 2 (pom.xml)
                - npm @qits/b 3 -> 4 (package.json)

                ## qits-artifacts
                # 2026.1001.1
                # qits-artifacts 2026.1001.1

                Release request `rr`.

                associated tickets: `abc-100`

                ## qits-ci
                # 2026.1009.5
                # qits-ci 2026.1009.5

                Release request `rr`.

                associated tickets: `qits-10`

                # 2026.1010.1
                # qits-ci 2026.1010.1

                Release request `rr`.

                associated tickets:

                # 2026.1010.20
                # qits-ci 2026.1010.20

                Release request `rr`.

                associated tickets: `qits-9` `qits-10`
                """);
        // Two coordinates name 2026.1010.20: it is read once.
        assertThat(store.paths()).filteredOn(p -> p.contains("2026.1010.20")).hasSize(1);
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer token");
    }

    @Test
    void noChangelogsIsTodaysMessage() throws IOException {
        Run run = run(payload(change("maven", "eu.wohlben.qits:a", null), change("npm", "@qits/b", null)),
                "maven\teu.wohlben.qits:a\nnpm\t@qits/b\n");

        assertThat(run.code()).as(run.err()).isZero();
        assertThat(run.out()).isEqualTo("""
                bump(targeted): 2 dependencies

                - maven eu.wohlben.qits:a 1 -> 2 (pom.xml)
                - npm @qits/b 3 -> 4 (package.json)
                """);
        assertThat(store.requests()).isEmpty();
    }

    @Test
    void aChangeTheStepDidNotApplyBringsNoChangelog() throws IOException {
        changelog("qits-ci", "2", doc("qits-ci", "2", " `qits-1`"));

        Run run = run(payload(change("maven", "eu.wohlben.qits:a", "qits-ci", "2"),
                        change("npm", "@qits/b", "qits-artifacts", "5")),
                "maven\teu.wohlben.qits:a\n");

        assertThat(run.code()).as(run.err()).isZero();
        assertThat(run.out()).startsWith("chore(qits-1): bump(targeted): 1 dependencies\n\n")
                .doesNotContain("qits-artifacts");
        assertThat(store.paths()).containsExactly("GET " + DOCS + "qits-ci/-/2/CHANGELOG.md");
    }

    @Test
    void aChangelogThatIsNotPublishedIsRefused() throws IOException {
        Run run = run(payload(change("maven", "eu.wohlben.qits:a", "qits-ci", "2")), "maven\teu.wohlben.qits:a\n");

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).contains("no changelog for qits-ci 2");
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aStoreFailureIsCouldNotAsk() throws IOException {
        store.on("GET", DOCS + "qits-ci/-/2/CHANGELOG.md", StubStore.Reply.of(503, "down"));

        Run run = run(payload(change("maven", "eu.wohlben.qits:a", "qits-ci", "2")), "maven\teu.wohlben.qits:a\n");

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.out()).isEmpty();
    }

    @Test
    void aChangelogWithoutItsLastLineIsRefused() throws IOException {
        changelog("qits-ci", "2", "# qits-ci 2\n\nno tickets line\n");

        Run run = run(payload(change("maven", "eu.wohlben.qits:a", "qits-ci", "2")), "maven\teu.wohlben.qits:a\n");

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).contains("qits-ci 2 is malformed");
    }

    @Test
    void noPayloadIsRefused() throws IOException {
        Run run = run(null, "maven\teu.wohlben.qits:a\n");

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.err()).contains("QITS_EVENT_PAYLOAD");
    }

    @Test
    void calendarVersionsSortNumerically() {
        assertThat(java.util.stream.Stream.of("2026.1010.20", "2026.909.3", "2026.1010.3", "2026.1010")
                .sorted(BumpMessageCommand.VERSION_ORDER).toList())
                .containsExactly("2026.909.3", "2026.1010", "2026.1010.3", "2026.1010.20");
    }
}
