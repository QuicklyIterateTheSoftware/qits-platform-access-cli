package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The fixtures F1 to F5 of the dossier page "The content hash v1", computed there with a reference
 * implementation of its rules. They pin the algorithm: if one of these fails, fix the Java, and if a
 * rule really has to change, that change is {@code v2}, never an edit of an expected value here.
 */
class ContentHashGoldenTest {

  @TempDir Path work;

  private static final byte[] INDEX = "{\"formatVersion\":1}\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] STATE = "[]\n".getBytes(StandardCharsets.UTF_8);

  @Test
  void f1AMavenContractJar() {
    byte[] jar = Archives.writeJar(List.of(
        new Archives.Entry("META-INF/", new byte[0]),
        new Archives.Entry("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n".getBytes(StandardCharsets.UTF_8)),
        new Archives.Entry("golden-masters/", new byte[0]),
        new Archives.Entry("golden-masters/index.json", INDEX),
        new Archives.Entry("golden-masters/states/", new byte[0]),
        new Archives.Entry("golden-masters/states/a.json", STATE)), true);

    assertEquals("v1:sha256:f8e47f30eefda8d066196888451be0ec1d08d9e53afed8b30cf71ba36ddb77c3",
        new MavenContentHash().of(BuiltPackage.maven(jar), Optional.empty(), List.of(), Reactor.NONE));
  }

  @Test
  void f1DoesNotDependOnHowTheJarWasWritten() {
    // Order, compression and timestamps are never read: only names and bytes.
    byte[] jar = Archives.writeJar(List.of(
        new Archives.Entry("golden-masters/states/a.json", STATE),
        new Archives.Entry("golden-masters/index.json", INDEX),
        new Archives.Entry("META-INF/maven/x/y/pom.properties", "noise".getBytes(StandardCharsets.UTF_8))), false);

    assertEquals("v1:sha256:f8e47f30eefda8d066196888451be0ec1d08d9e53afed8b30cf71ba36ddb77c3",
        new MavenContentHash().of(BuiltPackage.maven(jar), Optional.empty(), List.of(), Reactor.NONE));
  }

  @Test
  void f2TheSameTreeAsAnNpmTarball() {
    assertEquals("v1:sha256:3daeb481caf3ad0e7acd85f64a3722483cccc28e88ad2726d535eb71fb4b3ed1", f2("2026.1001.1"));
  }

  @Test
  void f2TheVersionIsNotPartOfTheHash() {
    assertEquals(f2("2026.1001.1"), f2("2026.1002.7"));
  }

  @Test
  void f2CanonicalFormOfTheManifest() {
    assertEquals(
        "{\"files\":[\"golden-masters/\"],\"license\":\"UNLICENSED\",\"name\":\"@qits/projects-golden-masters\"}",
        new String(CanonicalJson.manifestWithoutVersion(manifest("2026.1001.1")), StandardCharsets.UTF_8));
  }

  @Test
  void f3AMavenJarWithAnSbom() throws IOException {
    assertEquals(
        "qits-content-hash\tv1\n"
            + "type\tmaven\n"
            + "f\t65ab12a8ff3263fbc257e5ddf0aa563c64573d0bab1f1115b9b107834cfa6971\teu/wohlben/qits/Foo.class\n"
            + "s\tcyclonedx\n"
            + "c\tpkg:maven/org.slf4j/slf4j-api?type=jar\t2.0.16\tsha-256=aa\n"
            + "e\t<root>\tpkg:maven/org.slf4j/slf4j-api?type=jar\n",
        f3Manifest(F3));
    assertEquals("v1:sha256:82a7a47d7bb67808c1adc9f94ce2d429e8170b6389a2e10c7ab1fc84bd20fa6d", f3(F3));
  }

  @Test
  void f3bWhatEveryBuildChangesLeavesTheHashAlone() throws IOException {
    String f3b = """
        {"bomFormat":"CycloneDX","specVersion":"1.5","serialNumber":"urn:uuid:2",
         "metadata":{"timestamp":"2026-10-02T09:09:09Z","tools":[{"name":"y","version":"9"}],
           "component":{"bom-ref":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1002.1?type=jar","group":"eu.wohlben.qits","name":"qits-foo","version":"2026.1002.1","purl":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1002.1?type=jar"}},
         "components":[{"bom-ref":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","group":"org.slf4j","name":"slf4j-api","version":"2.0.16","purl":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","hashes":[{"alg":"SHA-256","content":"AA"}]}],
         "dependencies":[{"ref":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","dependsOn":[]},
                         {"ref":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1002.1?type=jar","dependsOn":["pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar"]}]}
        """;
    assertEquals(f3(F3), f3(f3b));
  }

  @Test
  void f4ADependencyBumpIsAChange() throws IOException {
    String f4 = F3.replace("slf4j-api@2.0.16", "slf4j-api@2.0.17").replace("\"version\":\"2.0.16\"", "\"version\":\"2.0.17\"");
    assertNotEquals(F3, f4);
    assertEquals("v1:sha256:af0b864c1808526394cfbf483b853c02aa4c4713921e9d8174b7da116d7cfb28", f3(f4));
  }

  @Test
  void f5AReactorModuleWithABundledAndALinkedSibling() throws IOException {
    Reactor reactor = new Reactor(Set.of("eu.wohlben.qits:qits-foo-core"),
        Map.of("eu.wohlben.qits:qits-foo-api", "2026.930.1"));
    assertEquals(
        "qits-content-hash\tv1\n"
            + "type\tmaven\n"
            + "f\t4bf5122f344554c53bde2ebb8cd2b7e3d1600ad631c385a5d7cce23c7785459a\teu/wohlben/qits/foo/client/Client.class\n"
            + "f\tdbc1b4c900ffe48d575b5da5c638040125f65db0fe3e24494b76ea986457d986\teu/wohlben/qits/foo/core/Core.class\n"
            + "s\tcyclonedx\n"
            + "c\tpkg:maven/eu.wohlben.qits/qits-foo-api?type=jar\t2026.930.1\t\n"
            + "c\tpkg:maven/org.slf4j/slf4j-api?type=jar\t2.0.16\tsha-256=aa\n"
            + "e\t<root>\tpkg:maven/eu.wohlben.qits/qits-foo-api?type=jar\n"
            + "e\t<root>\tpkg:maven/org.slf4j/slf4j-api?type=jar\n",
        f5Manifest("2026.1001.1", reactor));
    assertEquals("v1:sha256:0d605d782a6880dcd0223ccac9068e61c1ad34f2b4543cb2ba849ef1ceca929c", f5("2026.1001.1", reactor));
  }

  @Test
  void f5bTheNextReleaseWithNothingChangedHashesTheSame() throws IOException {
    Reactor reactor = new Reactor(Set.of("eu.wohlben.qits:qits-foo-core"),
        Map.of("eu.wohlben.qits:qits-foo-api", "2026.930.1"));
    assertEquals("v1:sha256:0d605d782a6880dcd0223ccac9068e61c1ad34f2b4543cb2ba849ef1ceca929c", f5("2026.1002.9", reactor));
  }

  @Test
  void f5cALinkedSiblingPublishedNowIsAChange() throws IOException {
    Reactor reactor = new Reactor(Set.of("eu.wohlben.qits:qits-foo-core"),
        Map.of("eu.wohlben.qits:qits-foo-api", "2026.1002.9"));
    assertEquals("v1:sha256:7d0a16baafb9a2fef8b7e709c07e2810e359757789cbb1c69f3a3fc52b5ad517", f5("2026.1002.9", reactor));
  }

  @Test
  void anSbomThatSaysNothingIsRefusedRatherThanHashedAsEmpty() throws IOException {
    Path sbom = work.resolve("empty.json");
    Files.writeString(sbom, "{\"bomFormat\":\"CycloneDX\",\"metadata\":{}}");
    CliException refused = org.junit.jupiter.api.Assertions.assertThrows(CliException.class,
        () -> SbomCanonical.lines(sbom, Reactor.NONE));
    assertEquals(ExitCode.POLICY, refused.code());
  }

  @Test
  void anIncludeThatMatchesNothingIsRefused() {
    byte[] jar = Archives.writeJar(List.of(new Archives.Entry("a/b.json", STATE)), false);
    CliException refused = org.junit.jupiter.api.Assertions.assertThrows(CliException.class,
        () -> new MavenContentHash().of(BuiltPackage.maven(jar), Optional.empty(), List.of("c/**"), Reactor.NONE));
    assertEquals(ExitCode.POLICY, refused.code());
  }

  @Test
  void aPathWithATabIsRefusedNeverEscaped() {
    byte[] jar = Archives.writeJar(List.of(new Archives.Entry("a\tb.json", STATE)), false);
    CliException refused = org.junit.jupiter.api.Assertions.assertThrows(CliException.class,
        () -> new MavenContentHash().of(BuiltPackage.maven(jar), Optional.empty(), List.of(), Reactor.NONE));
    assertEquals(ExitCode.POLICY, refused.code());
  }

  // --- fixtures ----------------------------------------------------------------------------------

  private static final String F3 = """
      {"bomFormat":"CycloneDX","specVersion":"1.6","serialNumber":"urn:uuid:1",
       "metadata":{"timestamp":"2026-10-01T00:00:00Z","tools":[{"name":"x","version":"1"}],
         "component":{"bom-ref":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1001.1?type=jar","group":"eu.wohlben.qits","name":"qits-foo","version":"2026.1001.1","purl":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1001.1?type=jar"}},
       "components":[{"bom-ref":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","group":"org.slf4j","name":"slf4j-api","version":"2.0.16","purl":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","hashes":[{"alg":"SHA-256","content":"AA"}]}],
       "dependencies":[{"ref":"pkg:maven/eu.wohlben.qits/qits-foo@2026.1001.1?type=jar","dependsOn":["pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar"]},
                       {"ref":"pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar","dependsOn":[]}]}
      """;

  private static byte[] manifest(String version) {
    return ("{\"name\":\"@qits/projects-golden-masters\",\"version\":\"" + version
        + "\",\"files\":[\"golden-masters/\"],\"license\":\"UNLICENSED\"}").getBytes(StandardCharsets.UTF_8);
  }

  private static String f2(String version) {
    byte[] tarball = Archives.writeTarGz(List.of(
        new Archives.Entry("package/package.json", manifest(version)),
        new Archives.Entry("package/golden-masters/index.json", INDEX),
        new Archives.Entry("package/golden-masters/states/a.json", STATE)));
    return new NpmContentHash().of(BuiltPackage.npm(tarball), Optional.empty(), List.of(), Reactor.NONE);
  }

  private static byte[] f3Jar() {
    return Archives.writeJar(List.of(
        new Archives.Entry("eu/wohlben/qits/Foo.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE})),
        false);
  }

  private String f3(String sbom) throws IOException {
    Path file = work.resolve("f3.json");
    Files.writeString(file, sbom);
    return new MavenContentHash().of(BuiltPackage.maven(f3Jar()), Optional.of(file), List.of(), Reactor.NONE);
  }

  private String f3Manifest(String sbom) throws IOException {
    Path file = work.resolve("f3.json");
    Files.writeString(file, sbom);
    HashManifest manifest = new HashManifest("maven", List.of());
    manifest.file("eu/wohlben/qits/Foo.class", new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
    manifest.sbom(SbomCanonical.lines(file, Reactor.NONE));
    return manifest.text();
  }

  private static String f5Sbom(String release) {
    String root = "pkg:maven/eu.wohlben.qits/qits-foo-client@" + release + "?type=jar";
    String core = "pkg:maven/eu.wohlben.qits/qits-foo-core@" + release + "?type=jar";
    String api = "pkg:maven/eu.wohlben.qits/qits-foo-api@" + release + "?type=jar";
    String slf4j = "pkg:maven/org.slf4j/slf4j-api@2.0.16?type=jar";
    return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\"urn:uuid:" + release + "\","
        + "\"metadata\":{\"component\":{\"bom-ref\":\"" + root + "\",\"group\":\"eu.wohlben.qits\","
        + "\"name\":\"qits-foo-client\",\"version\":\"" + release + "\",\"purl\":\"" + root + "\"}},"
        + "\"components\":["
        + "{\"bom-ref\":\"" + core + "\",\"group\":\"eu.wohlben.qits\",\"name\":\"qits-foo-core\",\"version\":\""
        + release + "\",\"purl\":\"" + core + "\"},"
        + "{\"bom-ref\":\"" + api + "\",\"group\":\"eu.wohlben.qits\",\"name\":\"qits-foo-api\",\"version\":\""
        + release + "\",\"purl\":\"" + api + "\"},"
        + "{\"bom-ref\":\"" + slf4j + "\",\"group\":\"org.slf4j\",\"name\":\"slf4j-api\",\"version\":\"2.0.16\","
        + "\"purl\":\"" + slf4j + "\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\"AA\"}]}],"
        + "\"dependencies\":["
        + "{\"ref\":\"" + root + "\",\"dependsOn\":[\"" + core + "\",\"" + api + "\"]},"
        + "{\"ref\":\"" + core + "\",\"dependsOn\":[\"" + slf4j + "\"]}]}";
  }

  private static byte[] f5Jar() {
    return Archives.writeJar(List.of(
        new Archives.Entry("eu/wohlben/qits/foo/client/Client.class", new byte[] {1}),
        new Archives.Entry("eu/wohlben/qits/foo/core/Core.class", new byte[] {2})), false);
  }

  private String f5(String release, Reactor reactor) throws IOException {
    Path file = work.resolve("f5.json");
    Files.writeString(file, f5Sbom(release));
    return new MavenContentHash().of(BuiltPackage.maven(f5Jar()), Optional.of(file), List.of(), reactor);
  }

  private String f5Manifest(String release, Reactor reactor) throws IOException {
    Path file = work.resolve("f5.json");
    Files.writeString(file, f5Sbom(release));
    HashManifest manifest = new HashManifest("maven", List.of());
    for (Archives.Entry entry : Archives.readJar(f5Jar(), "f5")) {
      manifest.file(entry.name(), entry.bytes());
    }
    manifest.sbom(SbomCanonical.lines(file, reactor));
    return manifest.text();
  }
}
