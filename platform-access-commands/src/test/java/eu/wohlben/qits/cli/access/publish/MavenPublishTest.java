package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code qits artifacts publish maven} against the reactor under {@code src/test/resources/reactor/}:
 * a parent importing an external BOM, {@code api}, {@code core} (on slf4j, BOM-managed) and
 * {@code client} (on core, api and a parent-managed util), and the pom-packaging product {@code bom}.
 * Each test copies it and writes the modules' jars, as a {@code package} build would have.
 */
class MavenPublishTest {

  private static final String V = "2026.1001.1";
  private static final String G = "eu.wohlben.qits";
  private static final String REPO = "/artifacts/maven/maven/eu/wohlben/qits/";
  private static final String CLIENT_JAR = REPO + "qits-foo-client/" + V + "/qits-foo-client-" + V + ".jar";
  private static final String CLIENT_POM = REPO + "qits-foo-client/" + V + "/qits-foo-client-" + V + ".pom";
  private static final String HASHES = "/artifacts/content-hashes/maven/";

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path reactor;

  @BeforeEach
  void setUp() throws Exception {
    store = new StubStore();
    cli = new Harness().store(store);
    store.on("GET", "/mirror/maven/central/org/example/example-bom/1.0/example-bom-1.0.pom", StubStore.Reply.of(200, """
        <project xmlns="http://maven.apache.org/POM/4.0.0">
          <modelVersion>4.0.0</modelVersion>
          <groupId>org.example</groupId><artifactId>example-bom</artifactId><version>1.0</version>
          <packaging>pom</packaging>
          <dependencyManagement><dependencies>
            <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.16</version></dependency>
          </dependencies></dependencyManagement>
        </project>
        """));
    reactor = work.resolve("reactor");
    Path source = Path.of(MavenPublishTest.class.getResource("/reactor/pom.xml").toURI()).getParent();
    try (Stream<Path> walk = Files.walk(source)) {
      for (Path path : (Iterable<Path>) walk::iterator) {
        Path target = reactor.resolve(source.relativize(path).toString());
        if (Files.isDirectory(path)) {
          Files.createDirectories(target);
        } else {
          Files.copy(path, target);
        }
      }
    }
    jar("api", "qits-foo-api", Map.of("eu/wohlben/qits/foo/api/Api.class", "api"));
    jar("core", "qits-foo-core", Map.of(
        "eu/wohlben/qits/foo/core/Core.class", "core",
        "META-INF/services/eu.wohlben.Spi", "eu.wohlben.CoreSpi\n"));
    jar("client", "qits-foo-client", Map.of(
        "eu/wohlben/qits/foo/client/Client.class", "client",
        "META-INF/services/eu.wohlben.Spi", "eu.wohlben.ClientSpi\n\neu.wohlben.CoreSpi\n"));
    for (String put : List.of(CLIENT_JAR, CLIENT_POM)) {
      store.on("PUT", put, StubStore.Reply.of(201, ""));
    }
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  /** A module's jar, with a manifest and the META-INF/maven noise a real build writes. */
  private void jar(String dir, String artifactId, Map<String, String> files) throws IOException {
    List<Archives.Entry> entries = new ArrayList<>();
    entries.add(new Archives.Entry("META-INF/", new byte[0]));
    entries.add(new Archives.Entry("META-INF/MANIFEST.MF", ("Manifest-Version: 1.0\r\nCreated-By: " + artifactId
        + "\r\n\r\n").getBytes(StandardCharsets.UTF_8)));
    entries.add(new Archives.Entry("META-INF/maven/" + G + "/" + artifactId + "/pom.properties",
        ("version=" + V + "\n").getBytes(StandardCharsets.UTF_8)));
    files.forEach((name, text) -> entries.add(new Archives.Entry(name, text.getBytes(StandardCharsets.UTF_8))));
    Path target = reactor.resolve(dir).resolve("target");
    Files.createDirectories(target);
    Files.write(target.resolve(artifactId + "-" + V + ".jar"), Archives.writeJar(entries, false));
  }

  private Harness.Run publish(String module, String name, String... extra) {
    List<String> argv = new ArrayList<>(List.of("maven", "--root", reactor.toString(), "--name", G + ":" + name,
        "--version", V, "--path", module));
    argv.addAll(List.of(extra));
    return cli.run(argv.toArray(String[]::new));
  }

  private StubStore.Request put(String path) {
    return store.requests().stream().filter(r -> r.method().equals("PUT") && r.path().equals(path))
        .reduce((a, b) -> b).orElseThrow(() -> new AssertionError("no PUT " + path + " in " + store.paths()));
  }

  private static Model pom(StubStore.Request request) throws Exception {
    return new MavenXpp3Reader().read(new StringReader(request.bodyText()));
  }

  private static Dependency dependency(Model model, String artifactId) {
    return model.getDependencies().stream().filter(d -> d.getArtifactId().equals(artifactId)).findFirst().orElse(null);
  }

  private static List<String> names(byte[] jar) {
    return Archives.readJar(jar, "jar").stream().map(Archives.Entry::name).toList();
  }

  private static String stored(String name, String version, String hash) {
    return "{\"ecosystem\":\"maven\",\"name\":\"" + name + "\",\"version\":\"" + version + "\",\"contentHash\":"
        + (hash == null ? "null" : "\"" + hash + "\"") + "}";
  }

  // --- flattening --------------------------------------------------------------------------------

  @Test
  void theFlattenedPomHasNoParentAndEveryVersionResolved() throws Exception {
    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("published " + V + "\n", run.out());
    Model pom = pom(put(CLIENT_POM));
    assertNull(pom.getParent());
    assertEquals(G, pom.getGroupId());
    assertEquals(V, pom.getVersion());
    assertEquals("jar", pom.getPackaging());
    assertNull(pom.getDependencyManagement());
    assertNull(pom.getBuild());
    assertTrue(pom.getProperties().isEmpty());
    // BOM-managed (imported by the parent) and property-managed versions, both resolved.
    assertEquals("2.0.16", dependency(pom, "slf4j-api").getVersion());
    assertEquals("3.1", dependency(pom, "util").getVersion());
    assertEquals("noise", dependency(pom, "util").getExclusions().get(0).getArtifactId());
    assertNull(dependency(pom, "junit-jupiter"), "test scope is never published");
    assertFalse(put(CLIENT_POM).bodyText().contains("${"), put(CLIENT_POM).bodyText());
    assertNotNull(put(CLIENT_POM).header("X-Artifacts-Content-Hash"));
    assertNull(put(CLIENT_JAR).header("X-Artifacts-Content-Hash"), "the hash goes on the pom, which is uploaded last");
    List<String> order = store.paths().stream().filter(p -> p.startsWith("PUT")).toList();
    assertEquals(List.of("PUT " + CLIENT_JAR, "PUT " + CLIENT_POM), order);
  }

  // --- bundling ----------------------------------------------------------------------------------

  @Test
  void aModuleBundlesItsSiblingsAndListsTheirExternals() throws Exception {
    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals(ExitCode.OK, run.code(), run.err());
    byte[] jar = put(CLIENT_JAR).body();
    List<String> names = names(jar);
    assertTrue(names.contains("eu/wohlben/qits/foo/client/Client.class"), names.toString());
    assertTrue(names.contains("eu/wohlben/qits/foo/core/Core.class"), names.toString());
    assertTrue(names.contains("eu/wohlben/qits/foo/api/Api.class"), names.toString());
    assertTrue(names.contains("META-INF/"), "directory entries are kept");
    assertTrue(names.stream().noneMatch(n -> n.startsWith("META-INF/maven/")), names.toString());
    Model pom = pom(put(CLIENT_POM));
    assertNotNull(dependency(pom, "slf4j-api"), "core's external dependency moves into the pom");
    assertNull(dependency(pom, "qits-foo-core"));
    assertNull(dependency(pom, "qits-foo-api"));
    String manifest = Archives.readJar(jar, "jar").stream().filter(e -> e.name().equals("META-INF/MANIFEST.MF"))
        .map(e -> new String(e.bytes(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    assertTrue(manifest.contains("qits-foo-client"), "the module's own manifest is kept: " + manifest);
  }

  @Test
  void servicesFilesAreMergedLineByLine() {
    publish("client", "qits-foo-client");

    String services = Archives.readJar(put(CLIENT_JAR).body(), "jar").stream()
        .filter(e -> e.name().equals("META-INF/services/eu.wohlben.Spi"))
        .map(e -> new String(e.bytes(), StandardCharsets.UTF_8)).findFirst().orElseThrow();
    assertEquals("eu.wohlben.ClientSpi\neu.wohlben.CoreSpi\n", services);
  }

  @Test
  void aConflictingDuplicateEntryIsRefused() throws IOException {
    jar("core", "qits-foo-core", Map.of("eu/wohlben/qits/foo/Shared.properties", "a=core\n"));
    jar("client", "qits-foo-client", Map.of("eu/wohlben/qits/foo/Shared.properties", "a=client\n"));

    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("eu/wohlben/qits/foo/Shared.properties is in both " + G + ":qits-foo-client and "
        + G + ":qits-foo-core"), run.err());
    assertFalse(store.paths().stream().anyMatch(p -> p.startsWith("PUT")));
  }

  @Test
  void theBundledJarIsTheSameBytesAcrossRuns() {
    publish("client", "qits-foo-client");
    byte[] first = put(CLIENT_JAR).body();
    publish("client", "qits-foo-client");
    byte[] second = put(CLIENT_JAR).body();

    assertArrayEquals(first, second);
  }

  @Test
  void aModuleWithNothingToBundleUploadsItsOwnJarUnchanged() throws IOException {
    String apiJar = REPO + "qits-foo-api/" + V + "/qits-foo-api-" + V + ".jar";
    store.on("PUT", apiJar, StubStore.Reply.of(201, ""));
    store.on("PUT", REPO + "qits-foo-api/" + V + "/qits-foo-api-" + V + ".pom", StubStore.Reply.of(201, ""));

    Harness.Run run = publish("api", "qits-foo-api");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertArrayEquals(Files.readAllBytes(reactor.resolve("api/target/qits-foo-api-" + V + ".jar")), put(apiJar).body());
  }

  // --- linking -----------------------------------------------------------------------------------

  @Test
  void aLinkedSiblingPublishedInThisReleaseIsADependencyAtTheReleaseVersion() throws Exception {
    store.on("GET", HASHES + G + ":qits-foo-api/-/" + V, StubStore.Reply.of(200, stored(G + ":qits-foo-api", V, null)));

    Harness.Run run = publish("client", "qits-foo-client", "--link", G + ":qits-foo-api");

    assertEquals(ExitCode.OK, run.code(), run.err());
    Model pom = pom(put(CLIENT_POM));
    assertEquals(V, dependency(pom, "qits-foo-api").getVersion());
    assertNull(dependency(pom, "qits-foo-core"), "core is still bundled");
    List<String> names = names(put(CLIENT_JAR).body());
    assertFalse(names.contains("eu/wohlben/qits/foo/api/Api.class"), names.toString());
    assertTrue(names.contains("eu/wohlben/qits/foo/core/Core.class"), names.toString());
  }

  @Test
  void aLinkedSiblingUnchangedSinceUIsADependencyAtU() throws Exception {
    store.on("GET", HASHES + G + ":qits-foo-api/-/newest",
        StubStore.Reply.of(200, stored(G + ":qits-foo-api", "2026.930.1", "v1:sha256:" + "a".repeat(64))));

    Harness.Run run = publish("client", "qits-foo-client", "--link", G + ":qits-foo-api");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals("2026.930.1", dependency(pom(put(CLIENT_POM)), "qits-foo-api").getVersion());
  }

  @Test
  void aLinkedSiblingNobodyDecidedIsRefused() {
    Harness.Run run = publish("client", "qits-foo-client", "--link", G + ":qits-foo-api");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("linked " + G + ":qits-foo-api has not been decided in this release"), run.err());
  }

  @Test
  void aLinkThatIsNotADependencyIsRefused() {
    Harness.Run run = publish("core", "qits-foo-core", "--link", G + ":qits-foo-api");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("is not a dependency of"), run.err());
  }

  // --- the hash ----------------------------------------------------------------------------------

  private Path sbom() throws IOException {
    Path sbom = work.resolve("client-sbom.json");
    String root = "pkg:maven/eu.wohlben.qits/qits-foo-client@" + V + "?type=jar";
    String core = "pkg:maven/eu.wohlben.qits/qits-foo-core@" + V + "?type=jar";
    String api = "pkg:maven/eu.wohlben.qits/qits-foo-api@" + V + "?type=jar";
    Files.writeString(sbom, "{\"metadata\":{\"component\":{\"bom-ref\":\"" + root + "\"}},\"components\":["
        + "{\"bom-ref\":\"" + core + "\",\"group\":\"" + G + "\",\"name\":\"qits-foo-core\",\"version\":\"" + V
        + "\",\"purl\":\"" + core + "\"},"
        + "{\"bom-ref\":\"" + api + "\",\"group\":\"" + G + "\",\"name\":\"qits-foo-api\",\"version\":\"" + V
        + "\",\"purl\":\"" + api + "\"}],"
        + "\"dependencies\":[{\"ref\":\"" + root + "\",\"dependsOn\":[\"" + core + "\",\"" + api + "\"]}]}");
    return sbom;
  }

  private String hashOfClient(String... extra) throws IOException {
    List<String> argv = new ArrayList<>(List.of("--sbom", sbom().toString()));
    argv.addAll(List.of(extra));
    Harness.Run run = publish("client", "qits-foo-client", argv.toArray(String[]::new));
    assertEquals(ExitCode.OK, run.code(), run.err());
    return put(CLIENT_POM).header("X-Artifacts-Content-Hash");
  }

  @Test
  void theHashIgnoresALinkedSiblingThatChangedButIsUnchangedInTheStore() throws IOException {
    store.on("GET", HASHES + G + ":qits-foo-api/-/newest",
        StubStore.Reply.of(200, stored(G + ":qits-foo-api", "2026.930.1", "v1:sha256:" + "a".repeat(64))));
    String before = hashOfClient("--link", G + ":qits-foo-api");

    jar("api", "qits-foo-api", Map.of("eu/wohlben/qits/foo/api/Api.class", "api, edited"));
    String after = hashOfClient("--link", G + ":qits-foo-api");

    assertEquals(before, after);
  }

  @Test
  void theHashChangesWhenABundledSiblingChanges() throws IOException {
    String before = hashOfClient();

    jar("core", "qits-foo-core", Map.of("eu/wohlben/qits/foo/core/Core.class", "core, edited"));
    String after = hashOfClient();

    assertNotEquals(before, after);
  }

  @Test
  void ifChangedUnchangedUploadsNothing() throws IOException {
    String hash = hashOfClient();
    store.on("GET", HASHES + G + ":qits-foo-client/-/newest",
        StubStore.Reply.of(200, stored(G + ":qits-foo-client", "2026.930.7", hash)));
    int puts = (int) store.paths().stream().filter(p -> p.startsWith("PUT")).count();

    Harness.Run run = publish("client", "qits-foo-client", "--sbom", sbom().toString(), "--if-changed");

    assertEquals("unchanged since 2026.930.7\n", run.out());
    assertEquals(puts, store.paths().stream().filter(p -> p.startsWith("PUT")).count());
  }

  // --- products, mismatches, re-runs -------------------------------------------------------------

  @Test
  void aPomPackagingProductKeepsItsDependencyManagement() throws Exception {
    String bomPom = REPO + "qits-foo-bom/" + V + "/qits-foo-bom-" + V + ".pom";
    store.on("PUT", bomPom, StubStore.Reply.of(201, ""));

    Harness.Run run = publish("bom", "qits-foo-bom");

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertEquals(List.of("PUT " + bomPom), store.paths().stream().filter(p -> p.startsWith("PUT")).toList());
    Model pom = pom(put(bomPom));
    assertEquals("pom", pom.getPackaging());
    assertNull(pom.getParent(), "a reactor parent is merged in, never referenced");
    List<String> managed = pom.getDependencyManagement().getDependencies().stream()
        .map(d -> d.getArtifactId() + ":" + d.getVersion()).toList();
    assertTrue(managed.contains("qits-foo-api:" + V), managed.toString());
    assertTrue(managed.contains("util:3.1"), managed.toString());
    assertEquals("3.1", pom.getProperties().getProperty("util.version"));
  }

  @Test
  void aPathThatIsAnotherModuleIsRefused() {
    Harness.Run run = publish("core", "qits-foo-client");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("core/pom.xml is " + G + ":qits-foo-core, not " + G + ":qits-foo-client"), run.err());
  }

  @Test
  void aVersionTheReactorDoesNotBuildIsRefused() {
    Harness.Run run = cli.run("maven", "--root", reactor.toString(), "--name", G + ":qits-foo-client", "--version",
        "2026.1001.2", "--path", "client");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("not --version 2026.1001.2"), run.err());
  }

  @Test
  void aJarTheStoreHoldsDifferentlyOnAReRunNamesTheOutputTimestamp() {
    store.on("PUT", CLIENT_JAR, StubStore.Reply.of(403, "different bytes"));

    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("project.build.outputTimestamp"), run.err());
    assertFalse(store.paths().contains("PUT " + CLIENT_POM));
  }

  @Test
  void aPomAlreadyThereWithoutAHashIsAReRunAndUploadsNothing() {
    store.on("GET", HASHES + G + ":qits-foo-client/-/" + V,
        StubStore.Reply.of(200, stored(G + ":qits-foo-client", V, null)));

    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals("published " + V + "\n", run.out());
    assertFalse(store.paths().stream().anyMatch(p -> p.startsWith("PUT")));
  }

  @Test
  void a5xxOnAnExternalBomIsExit2() {
    store.on("GET", "/mirror/maven/central/org/example/example-bom/1.0/example-bom-1.0.pom", StubStore.Reply.of(502, "down"));

    Harness.Run run = publish("client", "qits-foo-client");

    assertEquals(ExitCode.TRANSPORT, run.code(), run.err());
  }
}
