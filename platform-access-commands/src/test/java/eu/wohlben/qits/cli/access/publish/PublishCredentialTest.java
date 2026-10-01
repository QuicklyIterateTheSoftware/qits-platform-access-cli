package eu.wohlben.qits.cli.access.publish;

import eu.wohlben.qits.cli.access.FakeIdp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a publish presents, and where it got it.
 *
 * <p>Driven end to end through the real command tree against {@link StubStore}, because the thing
 * under test is the header on the wire: a test of {@link PublishCredential} alone would prove that
 * a token was resolved and not that anything sent it.
 */
class PublishCredentialTest {

    private static final String SBOM = "/artifacts/sboms/docker/qits/qits-ci/-/2026.906.1";
    private static final String DAEMON = "/artifacts/daemons/qits-ci-daemon/2026.906.1";

    private StubStore store;
    @TempDir
    Path work;
    private Path document;

    @BeforeEach
    void setUp() throws IOException {
        store = new StubStore();
        document = work.resolve("sbom.json");
        Files.writeString(document, "{\"bomFormat\":\"CycloneDX\"}");
        store.on("PUT", SBOM, StubStore.Reply.of(201, "{\"digest\":\"d\",\"sizeBytes\":1}"));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    // --- the four sources, in order ------------------------------------------------------------

    @Test
    void aTokenCommandIsRunAndItsOutputIsThePutsBearer() throws IOException {
        Path minter = script("printf 'minted-by-the-command'");

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, minter.toString())
                .run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer minted-by-the-command");
    }

    @Test
    void aTokenCommandWinsOverATokenAndOverTheCommissionedPair() throws IOException {
        Path minter = script("printf 'from-the-command'");

        new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, minter.toString())
                .with(PublishCredential.TOKEN, "from-the-variable")
                .with("QITS_COMMISSIONED_CLIENT_ID", "id")
                .with("QITS_COMMISSIONED_CLIENT_SECRET", "secret")
                .run(submit());

        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer from-the-command");
    }

    @Test
    void aTokenVariableIsSentVerbatim() {
        new Harness().store(store)
                .with(PublishCredential.TOKEN, "a-token-as-given")
                .run(submit());

        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer a-token-as-given");
    }

    @Test
    void theCommissionedPairIsMintedAtTheIdpWhenNothingElseSaysWhatToSend() throws IOException {
        try (FakeIdp idp = new FakeIdp()) {
            Harness.Run run = new Harness().store(store)
                    .with("QITS_COMMISSIONED_CLIENT_ID", idp.commissionedId)
                    .with("QITS_COMMISSIONED_CLIENT_SECRET", idp.commissionedSecret)
                    .with("QITS_GIT_AUTH_TOKEN_URL", idp.url() + "/token")
                    .run(submit());

            assertThat(run.code()).isEqualTo(ExitCode.OK);
            assertThat(store.lastRequest().header("Authorization")).startsWith("Bearer " + FakeIdp.SECRET);
            assertThat(idp.grants("client_credentials")).isEqualTo(1);
            assertThat(idp.requests).allSatisfy(request ->
                    assertThat(request).containsEntry("audience", "qits-platform"));
        }
    }

    @Test
    void anEnvironmentWithNoCredentialStillSendsThePutAndLetsTheStoreDecide() {
        Harness.Run run = new Harness().store(store).run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().header("Authorization")).isNull();
        assertThat(store.paths()).containsExactly("PUT " + SBOM);
    }

    @Test
    void aStoreThatRefusesAnAnonymousPublishAnswersForItself() {
        store.on("PUT", SBOM, StubStore.Reply.of(401, "only a CI run may publish"));

        Harness.Run run = new Harness().store(store).run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.POLICY);
        assertThat(run.err()).contains("HTTP 401").contains("only a CI run may publish");
    }

    // --- freshness -----------------------------------------------------------------------------

    @Test
    void theTokenCommandIsRunAgainForEveryRequestRatherThanOnce() throws IOException {
        // A counter in a file: the minter prints a different token each time it is asked, which is
        // the whole point of preferring a command over a token — an hour-long step must not present
        // the token it held when it started.
        Path counter = work.resolve("count");
        Files.writeString(counter, "0");
        Path minter = script("n=$(cat '" + counter + "'); n=$((n + 1));"
                + " printf '%s' \"$n\" > '" + counter + "'; printf 'token-%s' \"$n\"");
        Path binary = work.resolve("qits-ci-daemon");
        Files.write(binary, new byte[] {0x7f, 'E', 'L', 'F'});
        // A 409 makes the daemon publish two requests — a PUT and then a HEAD — in one command.
        store.on("PUT", DAEMON, StubStore.Reply.of(409, "already published"));
        store.on("HEAD", DAEMON, StubStore.Reply.of(200, "",
                java.util.Map.of("Docker-Content-Digest", Sha256.ofFile(binary))));

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, minter.toString())
                .run("daemon", "submit", "--name", "qits-ci-daemon", "--version", "2026.906.1",
                        "--file", binary.toString());

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.requests()).extracting(request -> request.header("Authorization"))
                .containsExactly("Bearer token-1", "Bearer token-2");
        assertThat(Files.readString(counter)).isEqualTo("2");
    }

    // --- the reads carry it too ----------------------------------------------------------------

    @Test
    void anExistsProbeIsAuthenticatedAsWell() {
        store.on("HEAD", DAEMON, StubStore.Reply.of(200, ""));

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN, "a-token")
                .run("exists", "daemon", "qits-ci-daemon", "2026.906.1");

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().method()).isEqualTo("HEAD");
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer a-token");
    }

    @Test
    void theNpmPackumentIsReadWithTheSameBearer() {
        store.on("GET", "/artifacts/npm/npm/@qits/ui-components",
                StubStore.Reply.of(200, "{\"versions\":{},\"dist-tags\":{}}"));

        Harness.Run run = new Harness()
                .with("QITS_NPM_REGISTRY_URL", store.url() + "/artifacts/npm/npm")
                .with(PublishCredential.TOKEN, "a-token")
                .run("npm", "plan", "--package", "@qits/ui-components", "--version", "2026.906.1");

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer a-token");
    }

    @Test
    void theDistTagPutIsAuthenticated() {
        store.on("PUT", "/artifacts/npm/npm/-/package/@qits/ui-components/dist-tags/main",
                StubStore.Reply.of(200, "{}"));

        Harness.Run run = new Harness()
                .with("QITS_NPM_REGISTRY_URL", store.url() + "/artifacts/npm/npm")
                .with(PublishCredential.TOKEN, "a-token")
                .run("npm", "dist-tag", "--package", "@qits/ui-components", "--version", "2026.906.1",
                        "--tag", "main");

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer a-token");
    }

    @Test
    void theDocsPutIsAuthenticatedBesideItsMetadataHeaders() throws IOException {
        Path archive = work.resolve("apidocs.tgz");
        Files.write(archive, new byte[] {1, 2, 3});
        store.on("PUT", "/artifacts/docs/docs/@apidocs/qits-ci/-/2026.906.1",
                StubStore.Reply.of(201, "{\"fileCount\":1,\"totalBytes\":3}"));

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN, "a-token")
                .run("docs", "submit", "--site", "@apidocs/qits-ci", "--version", "2026.906.1",
                        "--archive", archive.toString(), "--meta", "git.commit.hash=deadbeef");

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer a-token");
        assertThat(store.lastRequest().header("X-Artifacts-Meta-git.commit.hash")).isEqualTo("deadbeef");
    }

    // --- the token stays out of the output -------------------------------------------------------

    @Test
    void noTokenReachesStdoutOrStderrEvenWhenTheStoreRefuses() throws IOException {
        Path minter = script("printf 'SECRET-from-the-command'");
        store.on("PUT", SBOM, StubStore.Reply.of(400, "not a CycloneDX document"));

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, minter.toString())
                .with(PublishCredential.TOKEN, "SECRET-from-the-variable")
                .run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.POLICY);
        assertThat(run.out() + run.err()).doesNotContain("SECRET-");
    }

    @Test
    void aTokenCommandThatFailsSaysSoWithoutQuotingWhatItPrinted() throws IOException {
        Path minter = script("printf 'SECRET-half-a-token'; exit 3");

        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, minter.toString())
                .run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.TRANSPORT);
        assertThat(run.err()).contains("exited 3");
        assertThat(run.out() + run.err()).doesNotContain("SECRET-");
        assertThat(store.requests()).as("nothing is published without the token it was told to mint").isEmpty();
    }

    @Test
    void aTokenCommandThatIsNotThereWarnsAndFallsThroughToTheNextSource() {
        Harness.Run run = new Harness().store(store)
                .with(PublishCredential.TOKEN_COMMAND, work.resolve("no-such-minter").toString())
                .with(PublishCredential.TOKEN, "the-fallback")
                .run(submit());

        assertThat(run.code()).isEqualTo(ExitCode.OK);
        assertThat(run.err()).contains("is not an executable file");
        assertThat(store.lastRequest().header("Authorization")).isEqualTo("Bearer the-fallback");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** An executable shell script, which is what qits-ci writes at /tmp/qits-publish-token. */
    private Path script(String body) throws IOException {
        Path file = Files.createTempFile(work, "minter", ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"));
        return file;
    }

    private String[] submit() {
        return List.of("sbom", "submit", "--type", "docker", "--name", "qits/qits-ci", "--version",
                "2026.906.1", "--file", document.toString()).toArray(String[]::new);
    }
}
