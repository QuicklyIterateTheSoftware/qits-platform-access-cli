package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code qits artifacts publish npm}: every path of the decision, the tarball it builds, and the
 * publish document and tags around it.
 */
class NpmPublishTest {

  private static final String V = "2026.1002.1";
  private static final String PACKUMENT = "/artifacts/npm/npm/@qits/x";
  private static final String AT_V = "/artifacts/content-hashes/npm/@qits/x/-/" + V;
  private static final String NEWEST = "/artifacts/content-hashes/npm/@qits/x/-/newest";
  private static final String MAIN_TAG = "/artifacts/npm/npm/-/package/@qits/x/dist-tags/main";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path pkg;
  private Path sbom;

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    pkg = work.resolve("dist");
    Files.createDirectories(pkg.resolve("lib"));
    Files.writeString(pkg.resolve("package.json"),
        "{\"name\":\"@qits/x\",\"version\":\"" + V + "\",\"files\":[\"lib/\"],\"license\":\"UNLICENSED\"}");
    Files.writeString(pkg.resolve("lib/index.js"), "export const x = 1;\n");
    Files.writeString(pkg.resolve("README.md"), "# x\n");
    Files.writeString(pkg.resolve("notes.txt"), "not in files\n");
    Files.createDirectories(pkg.resolve("node_modules/y"));
    Files.writeString(pkg.resolve("node_modules/y/index.js"), "never packed\n");
    sbom = work.resolve("sbom.json");
    Files.writeString(sbom, "{\"components\":[{\"name\":\"tslib\",\"version\":\"2.8.1\",\"purl\":\"pkg:npm/tslib@2.8.1\"}]}");
    store.on("PUT", PACKUMENT, StubStore.Reply.of(201, "{\"ok\":true}"));
    store.on("PUT", MAIN_TAG, StubStore.Reply.of(200, "{}"));
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  private Harness.Run publish(String... extra) {
    List<String> argv = new java.util.ArrayList<>(List.of("npm", "--name", "@qits/x", "--version", V,
        "--path", pkg.toString(), "--sbom", sbom.toString()));
    argv.addAll(List.of(extra));
    return cli.run(argv.toArray(String[]::new));
  }

  private String hash() {
    return new NpmContentHash().of(BuiltPackage.npm(NpmPublisher.pack(pkg, "@qits/x", V).tarball()),
        Optional.of(sbom), List.of(), Reactor.NONE);
  }

  private static String stored(String version, String hash) {
    return "{\"ecosystem\":\"npm\",\"name\":\"@qits/x\",\"version\":\"" + version + "\",\"contentHash\":"
        + (hash == null ? "null" : "\"" + hash + "\"") + "}";
  }

  private boolean uploaded() {
    return store.paths().contains("PUT " + PACKUMENT);
  }

  // --- the decision ------------------------------------------------------------------------------

  @Test
  void firstPublish() throws IOException {
    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    StubStore.Request put = store.requests().stream()
        .filter(r -> r.method().equals("PUT") && r.path().equals(PACKUMENT)).findFirst().orElseThrow();
    assertEquals(hash(), put.header("X-Artifacts-Content-Hash"));
    JsonNode document = new ObjectMapper().readTree(put.body());
    assertEquals(V, document.path("dist-tags").path("latest").asText());
    assertEquals("@qits/x@" + V, document.path("versions").path(V).path("_id").asText());
    assertEquals(40, document.path("versions").path(V).path("dist").path("shasum").asText().length());
    assertTrue(store.paths().contains("PUT " + MAIN_TAG), store.paths().toString());
  }

  @Test
  void unchanged() {
    store.on("GET", NEWEST, StubStore.Reply.of(200, stored("2026.930.4", hash())));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("unchanged since 2026.930.4\n", run.out());
    assertFalse(uploaded());
    assertFalse(store.paths().contains("PUT " + MAIN_TAG));
  }

  @Test
  void changed() {
    store.on("GET", NEWEST, StubStore.Reply.of(200, stored("2026.930.4", "v1:sha256:" + "0".repeat(64))));

    Harness.Run run = publish("--if-changed");

    assertEquals("published " + V + "\n", run.out());
    assertTrue(uploaded());
  }

  @Test
  void theNewestVersionHasNoStoredHash() {
    store.on("GET", NEWEST, StubStore.Reply.of(200, stored("2026.930.4", null)));

    Harness.Run run = publish("--if-changed");

    assertEquals("published " + V + "\n", run.out());
    assertTrue(uploaded());
  }

  @Test
  void theNewestVersionWasHashedByAnotherAlgorithmVersion() {
    // Same hex, other version prefix: still "changed", never compared across versions.
    store.on("GET", NEWEST, StubStore.Reply.of(200, stored("2026.930.4", hash().replace("v1:", "v2:"))));

    Harness.Run run = publish("--if-changed");

    assertEquals("published " + V + "\n", run.out());
    assertTrue(uploaded());
  }

  @Test
  void a5xxOnTheNewestReadIsExit2AndNothingIsUploaded() {
    store.on("GET", NEWEST, StubStore.Reply.of(503, "down"));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertEquals("", run.out());
    assertFalse(uploaded());
  }

  @Test
  void anUnreadableNewestAnswerIsExit2() {
    store.on("GET", NEWEST, StubStore.Reply.of(200, "<html>"));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertFalse(uploaded());
  }

  @Test
  void a5xxOnTheVersionProbeIsExit2() {
    store.on("GET", AT_V, StubStore.Reply.of(500, "boom"));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertFalse(uploaded());
    assertFalse(store.paths().contains("GET " + NEWEST));
  }

  @Test
  void aReRunWithTheSameHashIsPublishedWithoutAnUpload() {
    store.on("GET", AT_V, StubStore.Reply.of(200, stored(V, hash())));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    assertFalse(uploaded());
    // The main tag is an idempotent repair, so a re-run moves it too.
    assertTrue(store.paths().contains("PUT " + MAIN_TAG));
  }

  @Test
  void aReRunOverAVersionWithNoHashIsPublishedWithoutAnUpload() {
    store.on("GET", AT_V, StubStore.Reply.of(200, stored(V, null)));

    Harness.Run run = publish("--if-changed");

    assertEquals("published " + V + "\n", run.out());
    assertFalse(uploaded());
  }

  @Test
  void aReRunOverOtherContentIsRefused() {
    store.on("GET", AT_V, StubStore.Reply.of(200, stored(V, "v1:sha256:" + "f".repeat(64))));

    Harness.Run run = publish("--if-changed");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("already holds other content"), run.err());
    assertFalse(uploaded());
  }

  @Test
  void withoutIfChangedTheNewestIsNeverAsked() {
    Harness.Run run = publish();

    assertEquals("published " + V + "\n", run.out());
    assertTrue(uploaded());
    assertFalse(store.paths().contains("GET " + NEWEST));
  }

  // --- the package -------------------------------------------------------------------------------

  @Test
  void aReplayTakesTheReplayTagAndLeavesMainAlone() throws IOException {
    store.on("GET", PACKUMENT, StubStore.Reply.of(200,
        "{\"dist-tags\":{\"latest\":\"2026.1005.1\"},\"versions\":{\"2026.1005.1\":{}}}"));

    Harness.Run run = publish("--if-changed");

    assertEquals("published " + V + "\n", run.out());
    StubStore.Request put = store.requests().stream()
        .filter(r -> r.method().equals("PUT") && r.path().equals(PACKUMENT)).findFirst().orElseThrow();
    JsonNode tags = new ObjectMapper().readTree(put.body()).path("dist-tags");
    assertEquals(V, tags.path("replay").asText());
    assertTrue(tags.path("latest").isMissingNode());
    assertFalse(store.paths().contains("PUT " + MAIN_TAG));
  }

  @Test
  void filesNarrowsWhatIsPackedAndNodeModulesIsNeverPacked() throws IOException {
    publish();

    StubStore.Request put = store.requests().stream()
        .filter(r -> r.method().equals("PUT") && r.path().equals(PACKUMENT)).findFirst().orElseThrow();
    JsonNode attachment = new ObjectMapper().readTree(put.body()).path("_attachments").path("@qits/x-" + V + ".tgz");
    byte[] tarball = Base64.getDecoder().decode(attachment.path("data").asText());
    assertEquals(tarball.length, attachment.path("length").asInt());
    List<String> names = Archives.readTarGz(tarball, "t").stream().map(Archives.Entry::name).toList();
    assertEquals(List.of("package/README.md", "package/lib/index.js", "package/package.json"), names);
  }

  @Test
  void theTarballIsTheSameBytesEveryTime() {
    assertTrue(java.util.Arrays.equals(NpmPublisher.pack(pkg, "@qits/x", V).tarball(),
        NpmPublisher.pack(pkg, "@qits/x", V).tarball()));
  }

  private static List<String> packed(Path dir) {
    return Archives.readTarGz(NpmPublisher.pack(dir, "@qits/x", V).tarball(), "t").stream()
        .map(Archives.Entry::name).toList();
  }

  /** What ng-packagr leaves in a dist: no "files", a secondary entry point, and its .npmignore. */
  private Path ngPackagrDist() throws IOException {
    Path dist = work.resolve("ng-dist");
    Files.createDirectories(dist.resolve("fesm2022"));
    Files.createDirectories(dist.resolve("testing"));
    Files.writeString(dist.resolve("package.json"),
        "{\"name\":\"@qits/x\",\"version\":\"" + V + "\",\"license\":\"UNLICENSED\"}");
    Files.writeString(dist.resolve("README.md"), "# x\n");
    Files.writeString(dist.resolve("index.d.ts"), "export {};\n");
    Files.writeString(dist.resolve("fesm2022/x.mjs"), "export const x = 1;\n");
    Files.writeString(dist.resolve("testing/package.json"), "{\"module\":\"../fesm2022/x-testing.mjs\"}");
    Files.writeString(dist.resolve("testing/index.d.ts"), "export {};\n");
    Files.writeString(dist.resolve(".npmignore"),
        "# Nested package.json's are only needed for development.\n**/package.json\n");
    return dist;
  }

  @Test
  void anNgPackagrNpmignoreDropsNestedManifestsButNeverTheRootOne() throws IOException {
    Path dist = ngPackagrDist();

    assertEquals(List.of("package/README.md", "package/fesm2022/x.mjs", "package/index.d.ts",
        "package/package.json", "package/testing/index.d.ts"), packed(dist));
  }

  @Test
  void npmignorePatternsFollowGitignore() throws IOException {
    Path dist = ngPackagrDist();
    Files.createDirectories(dist.resolve("src/deep"));
    Files.writeString(dist.resolve("src/deep/a.ts"), "a\n");
    Files.writeString(dist.resolve("deep.map"), "m\n");
    Files.writeString(dist.resolve("fesm2022/x.mjs.map"), "m\n");
    Files.writeString(dist.resolve("LICENSE"), "UNLICENSED\n");
    Files.writeString(dist.resolve(".npmignore"), "\n# comment\n/src/\n*.map\nLICENSE\ntesting\n");

    assertEquals(List.of("package/LICENSE", "package/README.md", "package/fesm2022/x.mjs",
        "package/index.d.ts", "package/package.json"), packed(dist));
  }

  @Test
  void withFilesTheNpmignoreIsNotRead() throws IOException {
    Files.writeString(pkg.resolve(".npmignore"), "lib/\n");

    assertEquals(List.of("package/README.md", "package/lib/index.js", "package/package.json"), packed(pkg));
  }

  @Test
  void anNpmignoreNegationIsRefused() throws IOException {
    Path dist = ngPackagrDist();
    Files.writeString(dist.resolve(".npmignore"), "**/package.json\n!testing/package.json\n");

    Harness.Run run = cli.run("npm", "--name", "@qits/x", "--version", V, "--path", dist.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("the negation \"!testing/package.json\" is not supported"), run.err());
    assertFalse(uploaded());
  }

  @Test
  void aNestedNpmignoreIsRefused() throws IOException {
    Path dist = ngPackagrDist();
    Files.writeString(dist.resolve("testing/.npmignore"), "*.d.ts\n");

    Harness.Run run = cli.run("npm", "--name", "@qits/x", "--version", V, "--path", dist.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("a .npmignore below the package root is not supported"), run.err());
    assertFalse(uploaded());
  }

  @Test
  void aManifestNamingAnotherPackageIsRefused() throws IOException {
    Harness.Run run = cli.run("npm", "--name", "@qits/y", "--version", V, "--path", pkg.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("is @qits/x, not @qits/y"), run.err());
  }

  @Test
  void aManifestAtAnotherVersionIsRefused() throws IOException {
    Harness.Run run = cli.run("npm", "--name", "@qits/x", "--version", "2026.1002.2", "--path", pkg.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("says version " + V), run.err());
  }

  @Test
  void ifChangedWithoutAnSbomIsRefused() {
    Harness.Run run = cli.run("npm", "--name", "@qits/x", "--version", V, "--path", pkg.toString(), "--if-changed");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("--if-changed needs --sbom"), run.err());
  }

  @Test
  void npmPlanStillAnswers() {
    Harness.Run run = cli.run("npm", "plan", "--package", "@qits/x", "--version", V);

    assertEquals("publish\n", run.out());
  }
}
