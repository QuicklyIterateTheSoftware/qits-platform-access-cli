package eu.wohlben.qits.cli.access.artifacts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.daemon.Sleeper;
import eu.wohlben.qits.cli.access.platform.CliContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code qits artifacts fetch deployed-pacts} against one fake edge that is both qits-maintenance and
 * qits-artifacts' hosted maven repository, in the token home (QITS_TOKEN), as a CI step runs it.
 */
class DeployedPactsCommandTest {

    private static final String PROVIDER = "qits-projects-service";
    private static final String CONSUMER = "qits-maintenance-service";
    private static final String ARTIFACT = CONSUMER + "-pacts-" + PROVIDER;
    private static final String DOOR = "/maintenance/api/deployed-pacts";
    private static final String OLD = "2026.1028.235307";
    private static final String NEW = "2026.1030.200000";
    private static final String PACT_FILE = CONSUMER + "_" + PROVIDER + ".json";

    @TempDir
    Path tmp;

    private FakePlatform platform;

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws IOException {
        platform = new FakePlatform();
    }

    @AfterEach
    void stop() {
        platform.close();
    }

    private static String jarPath(String version) {
        return "/artifacts/maven/maven/eu/wohlben/qits/" + ARTIFACT + "/" + version + "/" + ARTIFACT + "-" + version
                + ".jar";
    }

    private static String entry(String version, String... deployed) {
        return """
                {"consumerRepository":"%s","groupId":"eu.wohlben.qits","artifactId":"%s","pactVersion":"%s",
                 "deployedVersions":[%s]}""".formatted(CONSUMER, ARTIFACT, version,
                String.join(",", java.util.Arrays.stream(deployed).map(d -> "\"" + d + "\"").toList()));
    }

    private void answer(String... entries) {
        platform.answer("GET", DOOR, 200, "{\"provider\":\"" + PROVIDER + "\",\"pacts\":[" + String.join(",", entries)
                + "]}");
    }

    /** A pact package as {@code publish contract --kind pacts} builds it: pacts/, its file, a manifest. */
    private static byte[] jar(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("META-INF/"));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("pacts/"));
            zip.closeEntry();
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] pactJar(String marker) throws IOException {
        return jar(Map.of("pacts/" + PACT_FILE, "{\"pact\":\"" + marker + "\"}"));
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.of("QITS_TOKEN", "ci-run-token", "QITS_DOMAIN", "example.test",
                        "QITS_MAINTENANCE_URL", platform.url(), "QITS_ARTIFACTS_URL", platform.url()),
                InputStream.nullInputStream(), new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.systemUTC(), Sleeper.real(), name -> null,
                stop -> { });
        int exit = TestCli.execute(context, args);
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private Result fetch(Path out, String... more) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "artifacts", "fetch", "deployed-pacts", "--provider", PROVIDER, "--out", out.toString()));
        args.addAll(java.util.List.of(more));
        return run(args.toArray(String[]::new));
    }

    @Test
    void twoVersionsOfOneConsumerAreEachWrittenWhereTheLoaderReadsThem() throws IOException {
        answer(entry(OLD, "2026.1030.192107", "2026.1029.90000"), entry(NEW, "2026.1030.204903"));
        platform.file(jarPath(OLD), pactJar("old"));
        platform.file(jarPath(NEW), pactJar("new"));
        Path out = tmp.resolve("deployed-pacts");

        Result r = fetch(out);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("""
                fetched %s pact %s for deployed 2026.1030.192107, 2026.1029.90000
                fetched %s pact %s for deployed 2026.1030.204903
                fetched 2 pacts for %s into %s
                """.formatted(CONSUMER, OLD, CONSUMER, NEW, PROVIDER, out));
        assertThat(Files.readString(out.resolve(CONSUMER).resolve(OLD).resolve(PACT_FILE))).contains("old");
        assertThat(Files.readString(out.resolve(CONSUMER).resolve(NEW).resolve(PACT_FILE))).contains("new");
        try (var listed = Files.list(out.resolve(CONSUMER).resolve(OLD))) {
            assertThat(listed.map(p -> p.getFileName().toString())).containsExactly(PACT_FILE);
        }
        try (var listed = Files.list(out)) {
            assertThat(listed.map(p -> p.getFileName().toString())).as("no scratch jar left").containsExactly(CONSUMER);
        }
        assertThat(platform.requests("GET", DOOR)).singleElement().satisfies(q -> {
            assertThat(q.query()).isEqualTo("provider=" + PROVIDER);
            assertThat(q.authorization()).isEqualTo("Bearer ci-run-token");
        });
        assertThat(platform.requests("GET", jarPath(OLD)).getFirst().authorization()).isEqualTo("Bearer ci-run-token");
    }

    @Test
    void onlyThisProvidersTopLevelPactsAreUnpackedAndAnEarlierRunIsReplaced() throws IOException {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("pacts/" + PACT_FILE, "{\"pact\":\"mine\"}");
        files.put("pacts/" + CONSUMER + "_qits-ci-service.json", "{\"pact\":\"another provider's\"}");
        files.put("pacts/nested/" + PACT_FILE, "{\"pact\":\"nested\"}");
        files.put("golden-masters/" + PACT_FILE, "{\"pact\":\"elsewhere\"}");
        answer(entry(OLD, "2026.1030.192107"));
        platform.file(jarPath(OLD), jar(files));
        Path out = tmp.resolve("pacts");
        Path stale = out.resolve(CONSUMER).resolve(OLD).resolve("stale_" + PROVIDER + ".json");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "{}");
        Files.writeString(out.resolve(CONSUMER).resolve(OLD).resolve(PACT_FILE), "{\"pact\":\"before\"}");

        Result r = fetch(out);

        assertThat(r.exit()).as(r.err()).isZero();
        try (var listed = Files.list(out.resolve(CONSUMER).resolve(OLD))) {
            assertThat(listed.map(p -> p.getFileName().toString())).containsExactly(PACT_FILE);
        }
        assertThat(Files.readString(out.resolve(CONSUMER).resolve(OLD).resolve(PACT_FILE))).contains("mine");
        assertThat(r.out()).endsWith("fetched 1 pact for " + PROVIDER + " into " + out + "\n");
    }

    @Test
    void noDeployedConsumerIsSuccessAndStillCreatesTheDirectory() {
        answer();
        Path out = tmp.resolve("a").resolve("b");

        Result r = fetch(out);

        assertThat(r.exit()).as(r.err()).isZero();
        assertThat(r.out()).isEqualTo("no deployed consumer pacts for " + PROVIDER + "\n");
        assertThat(out).isDirectory().isEmptyDirectory();
    }

    @Test
    void maintenanceThatCannotSayWhatServesFailsAndFetchesNothing() {
        platform.answer("GET", DOOR, 503, "{\"message\":\"qits-deployments did not answer\"}");
        Path out = tmp.resolve("pacts");

        Result r = fetch(out);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Cannot list the deployed consumer pacts of " + PROVIDER)
                .contains("HTTP 503").contains("qits-deployments did not answer");
        assertThat(r.out()).isEmpty();
        assertThat(out).doesNotExist();
    }

    @Test
    void aJarWithoutThePactFails() throws IOException {
        answer(entry(OLD, "2026.1030.192107"));
        platform.file(jarPath(OLD), jar(Map.of("pacts/" + CONSUMER + "_qits-ci-service.json", "{}")));
        Path out = tmp.resolve("pacts");

        Result r = fetch(out);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("eu.wohlben.qits:" + ARTIFACT + ":" + OLD + " holds no pacts/*_" + PROVIDER
                + ".json");
        assertThat(out.resolve(CONSUMER)).doesNotExist();
    }

    @Test
    void aJarTheRegistryDoesNotHaveFails() {
        answer(entry(OLD, "2026.1030.192107"));
        Path out = tmp.resolve("pacts");

        Result r = fetch(out);

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("Cannot download eu.wohlben.qits:" + ARTIFACT + ":" + OLD).contains("HTTP 404");
    }

    @Test
    void aNameThatWouldLeaveTheDirectoryIsRefused() {
        platform.answer("GET", DOOR, 200, "{\"provider\":\"" + PROVIDER + "\",\"pacts\":[{\"consumerRepository\":"
                + "\"../etc\",\"groupId\":\"eu.wohlben.qits\",\"artifactId\":\"" + ARTIFACT + "\",\"pactVersion\":\""
                + OLD + "\",\"deployedVersions\":[]}]}");

        Result r = fetch(tmp.resolve("pacts"));

        assertThat(r.exit()).isEqualTo(1);
        assertThat(r.err()).contains("consumerRepository is not a plain name");
        assertThat(platform.requests).noneMatch(q -> q.path().endsWith(".jar"));
    }

    @Test
    void jsonPrintsTheAnswerWithTheFilesWritten() throws IOException {
        answer(entry(OLD, "2026.1030.192107"));
        platform.file(jarPath(OLD), pactJar("old"));
        Path out = tmp.resolve("pacts");

        Result r = fetch(out, "-o", "json");

        assertThat(r.exit()).as(r.err()).isZero();
        JsonNode printed = new ObjectMapper().readTree(r.out());
        assertThat(printed.path("provider").asText()).isEqualTo(PROVIDER);
        assertThat(printed.path("out").asText()).isEqualTo(out.toString());
        assertThat(printed.path("pacts").get(0).path("pactVersion").asText()).isEqualTo(OLD);
        assertThat(printed.path("pacts").get(0).path("files").get(0).asText())
                .isEqualTo(out.resolve(CONSUMER).resolve(OLD).resolve(PACT_FILE).toString());
    }

    @Test
    void theProviderAndTheDirectoryAreRequired() {
        Result r = run("artifacts", "fetch", "deployed-pacts", "--out", tmp.toString());

        assertThat(r.exit()).isEqualTo(2);
        assertThat(r.err()).contains("--provider");
        assertThat(platform.requests).isEmpty();
    }
}
