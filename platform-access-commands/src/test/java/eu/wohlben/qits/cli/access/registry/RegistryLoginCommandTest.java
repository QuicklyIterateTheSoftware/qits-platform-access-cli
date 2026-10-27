package eu.wohlben.qits.cli.access.registry;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakePlatform;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code qits registry-login} as a person types it, against a fake qits-artifacts and qits-mirror. */
class RegistryLoginCommandTest {

    private static final Instant T0 = Instant.parse("2026-10-27T10:00:00Z");

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakePlatform platform;
    private FakeTime time;
    private final Map<String, String> env = new HashMap<>();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        platform = new FakePlatform();
        time = new FakeTime(T0);
        env.put("HOME", home.toString());
        env.put("XDG_CONFIG_HOME", home.resolve(".config").toString());
        env.put("QITS_ARTIFACTS_URL", platform.url());
        env.put("QITS_MIRROR_URL", platform.url());
        new SessionFile(home.resolve(".config/qits")).write(new Session(idp.url(), "qits-cli",
                FakeIdp.SECRET + "access-0", T0.plus(Duration.ofMinutes(10)), idp.issueRefreshToken(),
                T0.plus(Duration.ofDays(30))));
        platform.answer("GET", "/artifacts/api/repositories", """
                {"repositories":[{"name":"npm","type":"npm-packages"},{"name":"maven","type":"maven-packages"},
                 {"name":"qits","type":"oci-images"}]}""");
        platform.answer("GET", "/mirror/api/repositories", """
                {"repositories":[{"name":"central","type":"maven-proxy"},{"name":"npmjs","type":"npm-proxy"}]}""");
    }

    @AfterEach
    void stop() {
        platform.close();
        idp.close();
    }

    private Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.copyOf(env), InputStream.nullInputStream(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, args);
        Result result = new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        assertThat(result.out() + result.err()).doesNotContain(FakeIdp.SECRET);
        return result;
    }

    private String key() {
        return "//" + platform.url().substring("http://".length());
    }

    @Test
    void configureWritesTheRegistriesThePlatformLists() throws Exception {
        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("Updated " + home.resolve(".npmrc"))
                .contains("Updated " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc")))
                .contains(key() + "/artifacts/npm/npm/:_authToken=" + FakeIdp.SECRET)
                .contains(key() + "/npm/npmjs/:_authToken=" + FakeIdp.SECRET)
                .doesNotContain("central").doesNotContain("qits/");
        assertThat(Files.readString(home.resolve(".m2/settings.xml")))
                .contains("<url>" + platform.url() + "/artifacts/maven/maven</url>")
                .contains("Bearer " + FakeIdp.SECRET);
        assertThat(home.resolve(".config/qits/registries.json")).exists();
        assertThat(platform.requests("GET", "/artifacts/api/repositories").getFirst().authorization())
                .startsWith("Bearer " + FakeIdp.SECRET);
        // Ten minutes left is more than the five a registry needs: no refresh.
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void aShortLivedTokenIsRefreshedFirst() throws Exception {
        time.jump(Duration.ofMinutes(7));

        assertThat(run("registry-login", "--configure").exit()).isZero();

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
    }

    @Test
    void anUnreadableListFallsBackToTheDefaults() throws Exception {
        platform.answer("GET", "/mirror/api/repositories", 500, "{}");

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isZero();
        assertThat(result.err()).contains("using the default registries");
        assertThat(Files.readString(home.resolve(".npmrc"))).contains(key() + "/npm/npmjs/:_authToken=");
    }

    @Test
    void withoutConfigureItRefreshesTheSetUpFilesOnce() throws Exception {
        assertThat(run("registry-login").exit()).isEqualTo(2);

        run("registry-login", "--configure");
        Files.writeString(home.resolve(".npmrc"), Files.readString(home.resolve(".npmrc"))
                .replace(FakeIdp.SECRET + "access-0", FakeIdp.SECRET + "stale"));
        Result again = run("registry-login");

        assertThat(again.exit()).isZero();
        assertThat(again.out()).contains("Updated " + home.resolve(".npmrc"))
                .contains("Up to date: " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc"))).doesNotContain("stale");
        // The list is read only by --configure.
        assertThat(platform.requests("GET", "/artifacts/api/repositories")).hasSize(1);
    }

    @Test
    void aDryRunPrintsARedactedDiffAndWritesNothing() throws Exception {
        Files.writeString(home.resolve(".npmrc"), "//registry.dev.localhost:8080/artifacts/npm/npm/:_auth="
                + FakeIdp.SECRET + "basic\n");

        Result result = run("registry-login", "--configure", "--remove-stale", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).contains("--- " + home.resolve(".npmrc") + "\n+++ " + home.resolve(".npmrc"))
                .contains("-//registry.dev.localhost:8080/artifacts/npm/npm/:_auth=<token>")
                .contains("+" + key() + "/artifacts/npm/npm/:_authToken=<token>")
                .contains("--- " + home.resolve(".m2/settings.xml"));
        assertThat(Files.readString(home.resolve(".npmrc"))).contains("dev.localhost");
        assertThat(home.resolve(".m2")).doesNotExist();
        assertThat(home.resolve(".config/qits/registries.json")).doesNotExist();
    }

    @Test
    void aDryRunWithNothingToDoSaysSo() {
        run("registry-login", "--configure");

        Result result = run("registry-login", "--dry-run");

        assertThat(result.exit()).isZero();
        assertThat(result.out()).isEqualTo("No changes\n");
    }

    @Test
    void itIsAWorkstationCommand() {
        env.put("QITS_TOKEN", FakeIdp.SECRET + "workspace");

        Result result = run("registry-login", "--configure");

        assertThat(result.exit()).isEqualTo(2);
        assertThat(result.err()).contains("for a workstation");
        assertThat(home.resolve(".npmrc")).doesNotExist();
    }

    @Test
    void pathFlagsNeedConfigure() {
        assertThat(run("registry-login", "--npmrc", "/tmp/x").exit()).isEqualTo(2);
    }
}
