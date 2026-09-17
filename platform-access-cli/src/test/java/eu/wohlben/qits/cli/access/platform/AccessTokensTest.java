package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessTokensTest {

    private static final Instant T0 = Instant.parse("2026-09-12T10:00:00Z");

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private SessionFile store;

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
    }

    private Session seed(Duration accessLeft, String refreshToken, Duration sessionLeft) throws Exception {
        Session session = new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-old", T0.plus(accessLeft),
                refreshToken, T0.plus(sessionLeft));
        store.write(session);
        return session;
    }

    private AccessTokens tokens(Clock clock) {
        return new AccessTokens(store, clock, TokenClient::new);
    }

    @Test
    void aGoodTokenIsUsedAsItIs() throws Exception {
        seed(Duration.ofMinutes(10), idp.issueRefreshToken(), Duration.ofDays(30));
        assertThat(tokens(time).session().accessToken()).isEqualTo(FakeIdp.SECRET + "access-old");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void aTokenAboutToExpireIsRefreshedAndWritten() throws Exception {
        String old = seed(Duration.ofSeconds(20), idp.issueRefreshToken(), Duration.ofDays(30)).refreshToken();

        Session got = tokens(time).session();

        assertThat(idp.grants("refresh_token")).isEqualTo(1);
        assertThat(idp.requests.getFirst()).containsEntry("refresh_token", old);
        assertThat(got.accessToken()).isNotEqualTo(FakeIdp.SECRET + "access-old");
        assertThat(store.read().orElseThrow()).isEqualTo(got);
        assertThat(got.accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
    }

    /**
     * The daemon refreshes while this command waits for the write lock. The command reads the file
     * again under the lock and uses that pair: a second refresh would present a spent token.
     */
    @Test
    void aRefreshMadeWhileWaitingForTheLockIsUsed() throws Exception {
        seed(Duration.ofSeconds(5), idp.issueRefreshToken(), Duration.ofDays(30));
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
        Session daemonWrote = new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "access-by-daemon",
                T0.plusSeconds(900), FakeIdp.SECRET + "refresh-by-daemon", T0.plus(Duration.ofDays(30)));

        CompletableFuture<Session> got;
        try (ExclusiveLock held = store.lockForWrite()) {
            got = CompletableFuture.supplyAsync(() -> {
                try {
                    return tokens(signalling).session();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            assertThat(readTheStaleFile.await(10, TimeUnit.SECONDS)).isTrue();
            store.write(daemonWrote);
        }

        assertThat(got.get(10, TimeUnit.SECONDS).accessToken()).isEqualTo(FakeIdp.SECRET + "access-by-daemon");
        assertThat(idp.grants("refresh_token")).isZero();
    }

    @Test
    void noSessionFileMeansNotSignedIn() {
        assertThatThrownBy(() -> tokens(time).session())
                .isInstanceOf(CliFailure.class)
                .hasMessage("Not signed in — run `qits login`.")
                .satisfies(e -> assertThat(((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE));
    }

    @Test
    void anEndedSessionSaysSoWithoutAskingTheIdp() throws Exception {
        seed(Duration.ofSeconds(-60), idp.issueRefreshToken(), Duration.ofSeconds(-1));
        assertThatThrownBy(() -> tokens(time).session()).hasMessage("Session ended — run `qits login`.");
        assertThat(idp.requests).isEmpty();
    }

    @Test
    void aRefusedRefreshEndsTheSession() throws Exception {
        seed(Duration.ofSeconds(-60), FakeIdp.SECRET + "revoked", Duration.ofDays(30));
        assertThatThrownBy(() -> tokens(time).session())
                .isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("Session ended — run `qits login`.")
                .hasMessageContaining("invalid_grant")
                .hasMessageNotContaining(FakeIdp.SECRET)
                .satisfies(e -> assertThat(((CliFailure) e).exitCode()).isEqualTo(CliFailure.USAGE));
    }

    @Test
    void anUnreachableIdpIsWorthAnotherTry() throws Exception {
        seed(Duration.ofSeconds(-60), idp.issueRefreshToken(), Duration.ofDays(30));
        idp.failNext(503);
        assertThatThrownBy(() -> tokens(time).session())
                .isInstanceOf(CliFailure.class)
                .hasMessageStartingWith("Cannot refresh the session")
                .hasMessageContaining("HTTP 503")
                .satisfies(e -> assertThat(((CliFailure) e).retryable()).isTrue());
    }
}
