package eu.wohlben.qits.cli.access.git;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.TestCli;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.platform.CliContext;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class GitCredentialCommandTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");
    private static final String ORIGIN = "https://githost.dev.wohlben.eu";
    private static final String REQUEST = "protocol=https\nhost=githost.dev.wohlben.eu\npath=git/qits/qits-ci.git\n\n";
    private static final String OLD_ACCESS = FakeIdp.SECRET + "access-git";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private GitCredentialFile store;
    private final ByteArrayOutputStream allErr = new ByteArrayOutputStream();

    record Result(int exit, String out, String err) {
    }

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new GitCredentialFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        // stdout carries a token by design, for Git; stderr never does.
        assertThat(allErr.toString(StandardCharsets.UTF_8)).doesNotContain(FakeIdp.SECRET);
    }

    private Result credential(String action, String stdin) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliContext context = new CliContext(Map.of("XDG_CONFIG_HOME", home.toString()),
                new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                time, time, TokenClient::new, stop -> { });
        int exit = TestCli.execute(context, "git-credential", action);
        allErr.writeBytes(err.toByteArray());
        return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private GitCredential seed(Duration accessLeft, String refreshToken, Duration signInLeft) throws Exception {
        GitCredential credential = new GitCredential(idp.url(), GitLoginFlow.CLIENT_ID, "dev-qits-githost", ORIGIN,
                OLD_ACCESS, T0.plus(accessLeft), refreshToken, T0.plus(signInLeft));
        try (ExclusiveLock ignored = store.lock()) {
            store.put(credential);
        }
        return credential;
    }

    private static String answer(String token) {
        return "username=oauth2\npassword=" + token + "\n\n";
    }

    @Test
    void getHandsOverAFreshTokenAsItIs() throws Exception {
        seed(Duration.ofMinutes(5), idp.issueRefreshToken(), Duration.ofDays(30));

        Result r = credential("get", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEqualTo(answer(OLD_ACCESS));
        assertThat(r.err()).isEmpty();
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void getRefreshesATokenWithAMinuteLeftAndWritesBeforePrinting() throws Exception {
        String old = seed(Duration.ofSeconds(59), idp.issueRefreshToken(), Duration.ofDays(30)).refreshToken();

        Result r = credential("get", REQUEST);

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(idp.requests.getFirst()).containsEntry("client_id", "qits-git-workstation")
                .containsEntry("refresh_token", old).containsEntry("audience", "dev-qits-githost");
        GitCredential now = store.find(ORIGIN).orElseThrow();
        assertThat(now.refreshToken()).isNotEqualTo(old);
        assertThat(now.accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(r.out()).isEqualTo(answer(now.accessToken()));
    }

    /** Two Git processes at once: the second waits for the lock and uses the first one's refresh. */
    @Test
    void aRefreshMadeWhileWaitingForTheLockIsUsed() throws Exception {
        seed(Duration.ofSeconds(10), idp.issueRefreshToken(), Duration.ofDays(30));
        CountDownLatch readTheStaleFile = new CountDownLatch(1);
        Clock signalling = new Clock() {
            @Override
            public Instant instant() {
                readTheStaleFile.countDown();
                return time.instant();
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        GitCredential refreshedElsewhere = new GitCredential(idp.url(), GitLoginFlow.CLIENT_ID, "dev-qits-githost",
                ORIGIN, FakeIdp.SECRET + "access-elsewhere", T0.plusSeconds(900), FakeIdp.SECRET + "refresh-elsewhere",
                T0.plus(Duration.ofDays(30)));
        PrintStream err = new PrintStream(allErr, true, StandardCharsets.UTF_8);

        CompletableFuture<Optional<String>> got;
        try (ExclusiveLock held = store.lock()) {
            got = CompletableFuture.supplyAsync(() -> {
                try {
                    return new GitAccess(store, signalling).accessToken(ORIGIN, err);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(readTheStaleFile.await(10, TimeUnit.SECONDS)).isTrue();
            store.put(refreshedElsewhere);
        }

        assertThat(got.get(10, TimeUnit.SECONDS)).contains(FakeIdp.SECRET + "access-elsewhere");
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void eraseDropsTheAccessTokenAndKeepsTheSignIn() throws Exception {
        GitCredential seeded = seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        Result r = credential("erase", REQUEST.replace("\n\n", "\nusername=oauth2\npassword=" + OLD_ACCESS + "\n\n"));

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        GitCredential now = store.find(ORIGIN).orElseThrow();
        assertThat(now.accessToken()).isNull();
        assertThat(now.refreshToken()).isEqualTo(seeded.refreshToken());
        assertThat(idp.requests).isEmpty();

        // The next get refreshes instead of asking for a new sign-in.
        Result next = credential("get", REQUEST);
        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(next.out()).isEqualTo(answer(store.find(ORIGIN).orElseThrow().accessToken()));
    }

    @Test
    void eraseOfAnotherTokenLeavesTheStoredOne() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        credential("erase", REQUEST.replace("\n\n", "\npassword=an-older-token\n\n"));

        assertThat(store.find(ORIGIN).orElseThrow().accessToken()).isEqualTo(OLD_ACCESS);
    }

    @Test
    void anOriginWithoutASignInGetsNoAnswer() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));

        Result other = credential("get", "protocol=https\nhost=github.com\n\n");
        Result noHost = credential("get", "protocol=https\n\n");

        assertThat(other).isEqualTo(new Result(0, "", ""));
        assertThat(noHost).isEqualTo(new Result(0, "", ""));
        assertThat(credential("get", REQUEST.replace("githost.dev", "githost.prod"))).isEqualTo(new Result(0, "", ""));
    }

    @Test
    void noFileAtAllGetsNoAnswer() {
        assertThat(credential("get", REQUEST)).isEqualTo(new Result(0, "", ""));
        assertThat(store.path()).doesNotExist();
    }

    @Test
    void aRefusedRefreshSaysToSignInAgainAndPrintsNothing() throws Exception {
        seed(Duration.ofSeconds(-5), FakeIdp.SECRET + "revoked", Duration.ofDays(30));

        Result r = credential("get", REQUEST);

        assertThat(r.exit()).isZero();
        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Git sign-in ended — run `qits git-login`.").contains("invalid_grant");
    }

    @Test
    void anEndedSignInIsNotRefreshed() throws Exception {
        seed(Duration.ofSeconds(-5), idp.issueRefreshToken(), Duration.ofSeconds(-1));

        Result r = credential("get", REQUEST);

        assertThat(r.out()).isEmpty();
        assertThat(r.err()).contains("Git sign-in ended");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void storeIsIgnored() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));
        String before = Files.readString(store.path());

        Result r = credential("store", REQUEST.replace("\n\n", "\nusername=someone\npassword=something\n\n"));

        assertThat(r).isEqualTo(new Result(0, "", ""));
        assertThat(Files.readString(store.path())).isEqualTo(before);
    }
}
