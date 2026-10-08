package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The idempotency matrix for the sbom wire, which is the cheapest of the three: an occupied
 * coordinate answers 200 with the stored digest, so the PUT that was going to happen anyway settles
 * whether the bytes agree.
 */
class SbomSubmitTest {

  private StubStore store;
  private Harness cli;
  @TempDir Path work;
  private Path document;

  private static final String PATH = "/artifacts/sboms/docker/qits/qits-ci/-/2026.906.1";

  @BeforeEach
  void setUp() throws IOException {
    store = new StubStore();
    cli = new Harness().store(store);
    document = work.resolve("sbom.json");
    Files.writeString(document, "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}");
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  @Test
  void anAbsentCoordinateIsPublishedAndTheReceiptIsReadBack() {
    store.on(
        "PUT",
        PATH,
        StubStore.Reply.of(
            201, "{\"digest\":\"" + Sha256.ofFile(document) + "\",\"sizeBytes\":42}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("published the sbom for docker qits/qits-ci@2026.906.1"), run.err());
    assertTrue(run.errContains("42 bytes"), run.err());
    assertEquals(
        "application/vnd.cyclonedx+json", store.lastRequest().header("Content-Type"));
    assertEquals("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\"}", store.lastRequest().bodyText());
  }

  @Test
  void anOccupiedCoordinateHoldingTheSameBytesIsSuccessAndSaysSo() {
    store.on(
        "PUT",
        PATH,
        StubStore.Reply.of(
            200,
            "{\"alreadyPublished\":true,\"digest\":\"" + Sha256.ofFile(document) + "\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("already published with the same bytes"), run.err());
    assertTrue(run.errContains("verified"), run.err());
  }

  @Test
  void anOccupiedCoordinateHoldingDifferentBytesFailsNamingBothDigests() {
    String other = "sha256:" + "ab".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true,\"digest\":\"" + other + "\"}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("DIFFERENT bytes"), run.err());
    assertTrue(run.errContains(other), run.err());
    assertTrue(run.errContains(Sha256.ofFile(document)), run.err());
  }

  @Test
  void aRebuildThatDiffersOnlyInSerialNumberAndTimestampIsVerified() throws IOException {
    Files.writeString(document, rebuilt("urn:uuid:2222", "2026-10-08T12:00:00Z", "2.17.2", "aa11"));
    String storedDocument =
        "{\n  \"specVersion\": \"1.6\",\n  \"bomFormat\": \"CycloneDX\",\n"
            + "  \"metadata\": {\"component\": {\"name\": \"qits-ci\"},"
            + " \"timestamp\": \"2026-09-06T08:00:00Z\"},\n"
            + "  \"serialNumber\": \"urn:uuid:1111\",\n  \"version\": 1.0,\n"
            + "  \"components\": [{\"name\": \"jackson\", \"version\": \"2.17.2\","
            + " \"hashes\": [{\"alg\": \"SHA-256\", \"content\": \"aa11\"}]}]\n}";
    String other = "sha256:" + "ab".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true,\"digest\":\"" + other + "\"}"));
    store.on("GET", PATH, StubStore.Reply.of(200, storedDocument));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code(), run.err());
    assertTrue(
        run.errContains(
            "the sbom for docker qits/qits-ci@2026.906.1 is already published as "
                + other
                + "; this run produced "
                + Sha256.ofFile(document)
                + ", which differs only in serialNumber and metadata.timestamp — verified"),
        run.err());
  }

  @Test
  void aRebuildWhoseComponentsDifferStillFailsAsDifferentBytes() throws IOException {
    Files.writeString(document, rebuilt("urn:uuid:2222", "2026-10-08T12:00:00Z", "2.17.3", "bb22"));
    String other = "sha256:" + "ab".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true,\"digest\":\"" + other + "\"}"));
    store.on(
        "GET",
        PATH,
        StubStore.Reply.of(200, rebuilt("urn:uuid:1111", "2026-09-06T08:00:00Z", "2.17.2", "aa11")));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("DIFFERENT bytes"), run.err());
    assertTrue(run.errContains(other), run.err());
  }

  @Test
  void aStoredDocumentThatCannotBeReadBackFailsAsDifferentBytes() throws IOException {
    Files.writeString(document, rebuilt("urn:uuid:2222", "2026-10-08T12:00:00Z", "2.17.2", "aa11"));
    String other = "sha256:" + "ab".repeat(32);
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true,\"digest\":\"" + other + "\"}"));
    store.on("GET", PATH, StubStore.Reply.of(500, "the blob store is away"));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("DIFFERENT bytes"), run.err());
    assertTrue(run.errContains(other), run.err());
    assertTrue(run.errContains(Sha256.ofFile(document)), run.err());
  }

  /** A CycloneDX document in the shape a build writes, with the fields a rebuild may vary. */
  private static String rebuilt(String serial, String timestamp, String version, String hash) {
    return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"serialNumber\":\""
        + serial
        + "\",\"version\":1,\"metadata\":{\"timestamp\":\""
        + timestamp
        + "\",\"component\":{\"name\":\"qits-ci\"}},\"components\":[{\"name\":\"jackson\","
        + "\"version\":\""
        + version
        + "\",\"hashes\":[{\"alg\":\"SHA-256\",\"content\":\""
        + hash
        + "\"}]}]}";
  }

  @Test
  void anOccupiedCoordinateThatCarriesNoDigestDegradesLoudlyRatherThanSilently() {
    store.on("PUT", PATH, StubStore.Reply.of(200, "{\"alreadyPublished\":true}"));

    Harness.Run run = submit();

    assertEquals(ExitCode.OK, run.code());
    assertTrue(run.errContains("WARN:"), run.err());
    assertTrue(run.errContains("NOT verified"), run.err());
  }

  @Test
  void aStoreThatFailsIsNotARefusalAndExitsTwo() {
    store.on("PUT", PATH, StubStore.Reply.of(503, "the database is away"));

    Harness.Run run = submit();

    assertEquals(ExitCode.TRANSPORT, run.code());
    assertTrue(run.errContains("HTTP 503"), run.err());
    assertTrue(run.errContains("the database is away"), run.err());
  }

  @Test
  void aBadRequestIsARefusalAndExitsOne() {
    store.on("PUT", PATH, StubStore.Reply.of(400, "not a CycloneDX document"));

    Harness.Run run = submit();

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("HTTP 400"), run.err());
  }

  @Test
  void aPackageTypeTheStoreDoesNotServeIsRefusedBeforeAnythingIsSent() {
    Harness.Run run =
        cli.run(
            "sbom", "submit", "--type", "gem", "--name", "x", "--version", "1", "--file",
            document.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("unknown package type 'gem'"), run.err());
    assertTrue(store.requests().isEmpty(), "nothing should have been sent");
  }

  @Test
  void anUnreachableStoreIsCouldNotAskRatherThanRefused() {
    Harness.Run run =
        new Harness()
            .run(
                "sbom", "submit", "--type", "docker", "--name", "x", "--version", "1", "--file",
                document.toString());

    assertEquals(ExitCode.TRANSPORT, run.code());
  }

  private Harness.Run submit() {
    return cli.run(
        "sbom",
        "submit",
        "--type",
        "docker",
        "--name",
        "qits/qits-ci",
        "--version",
        "2026.906.1",
        "--file",
        document.toString());
  }

  @Test
  void aNameThatTriesToTraverseIsRefused() {
    Harness.Run run =
        cli.run(
            "sbom", "submit", "--type", "docker", "--name", "qits/../daemons", "--version", "1",
            "--file", document.toString());

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("traverses"), run.err());
    assertTrue(store.requests().isEmpty());
  }

  @Test
  void theDocumentIsSentUnderTheCoordinateTheArgumentsSpell() {
    store.on("PUT", PATH, StubStore.Reply.of(201, "{\"digest\":\"d\",\"sizeBytes\":1}"));
    submit();
    assertEquals("PUT " + PATH, store.paths().get(0));
  }
}
