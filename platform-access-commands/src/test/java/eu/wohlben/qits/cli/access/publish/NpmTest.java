package eu.wohlben.qits.cli.access.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code npm plan} and {@code npm dist-tag} — the reasoning that used to live inline in every jslib
 * and frontend pipeline.
 */
class NpmTest {

  private static final String PACKUMENT = "/artifacts/npm/npm/@qits/ui-components";

  private StubStore store;
  private Harness cli;

  @BeforeEach
  void setUp() {
    store = new StubStore();
    cli = new Harness().store(store);
  }

  @AfterEach
  void tearDown() {
    store.close();
  }

  // --- plan --------------------------------------------------------------------------------------

  @Test
  void aPackageWithNothingPublishedPlansAPublish() {
    assertEquals("publish\n", plan("2026.906.1").out());
  }

  @Test
  void aVersionTheRegistryAlreadyHoldsPlansASkip() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.1\"},\"versions\":{\"2026.906.1\":{}}}"));

    Harness.Run run = plan("2026.906.1");
    assertEquals("skip\n", run.out());
    assertEquals(ExitCode.OK, run.code());
  }

  @Test
  void aVersionBelowLatestPlansAReplay() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.9\"},\"versions\":{\"2026.906.9\":{}}}"));

    Harness.Run run = plan("2026.905.1");
    assertEquals("publish-replay\n", run.out());
    assertTrue(run.errContains("is below latest 2026.906.9"), run.err());
  }

  @Test
  void aVersionAboveLatestPlansAnOrdinaryPublish() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200, "{\"dist-tags\":{\"latest\":\"2026.906.9\"},\"versions\":{\"2026.906.9\":{}}}"));

    assertEquals("publish\n", plan("2026.907.1").out());
  }

  @Test
  void aNonNumericSegmentInLatestCountsAsZeroTheWayTheShellComparatorDid() {
    // The registry really holds one of these: @qits/ui-components@2026.902.204627-main.geec9f2c
    // froze there when the per-push pipeline was retired. `Number("204627-main")` is NaN, NaN is
    // falsy, and `NaN || 0` is 0 — so latest reads as 2026.902.0 and a 2026.902.1 is above it.
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(
            200,
            "{\"dist-tags\":{\"latest\":\"2026.902.204627-main.geec9f2c\"},\"versions\":{}}"));

    assertEquals("publish\n", plan("2026.902.1").out());
  }

  @Test
  void anUnreachableRegistryIsNotAPublishDecision() {
    store.on("GET", PACKUMENT, StubStore.Reply.of(500, "away"));

    Harness.Run run = plan("2026.906.1");
    assertEquals(ExitCode.TRANSPORT, run.code());
    assertEquals("", run.out());
  }

  @Test
  void thePlanIsTheOnlyThingOnStdoutSoACommandSubstitutionCapturesJustTheWord() {
    store.on(
        "GET",
        PACKUMENT,
        StubStore.Reply.of(200, "{\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{}}"));

    Harness.Run run = plan("2026.906.1");
    assertEquals("publish\n", run.out());
    assertTrue(!run.err().isEmpty(), "the reasoning still belongs in the log");
  }

  // --- dist-tag ----------------------------------------------------------------------------------

  @Test
  void aDistTagIsPutAsAJsonStringUnderTheRepositoryScopedNamespace() {
    String path = "/artifacts/npm/npm/-/package/@qits/ui-components/dist-tags/main";
    store.on("PUT", path, StubStore.Reply.of(200, "{\"latest\":\"2026.906.1\",\"main\":\"2026.906.1\"}"));

    Harness.Run run =
        cli.run(
            "npm", "dist-tag", "--package", "@qits/ui-components", "--version", "2026.906.1",
            "--tag", "main");

    assertEquals(ExitCode.OK, run.code());
    assertEquals("\"2026.906.1\"", store.lastRequest().bodyText());
    assertEquals("application/json", store.lastRequest().header("Content-Type"));
    assertTrue(run.errContains("the main dist-tag now names"), run.err());
  }

  @Test
  void aRefusedBackwardsMoveOfLatestIsARealFailureRatherThanSwallowed() {
    String path = "/artifacts/npm/npm/-/package/@qits/ui-components/dist-tags/latest";
    store.on("PUT", path, StubStore.Reply.of(403, "latest only moves forward"));

    Harness.Run run =
        cli.run(
            "npm", "dist-tag", "--package", "@qits/ui-components", "--version", "1.0.0", "--tag",
            "latest");

    assertEquals(ExitCode.POLICY, run.code());
    assertTrue(run.errContains("HTTP 403"), run.err());
    assertTrue(run.errContains("latest only moves forward"), run.err());
  }

  // --- no lockfile rewriting ---------------------------------------------------------------------

  @Test
  void aLockfileIsNeverRewrittenSoTheVerbThatDidItIsGone() {
    Harness.Run run = cli.run("npm", "rewrite-lockfile-origin", "--lockfile", "package-lock.json");

    assertEquals(2, run.code());
    assertTrue(run.errContains("rewrite-lockfile-origin"), run.err());
  }

  private Harness.Run plan(String version) {
    return cli.run("npm", "plan", "--package", "@qits/ui-components", "--version", version);
  }
}
