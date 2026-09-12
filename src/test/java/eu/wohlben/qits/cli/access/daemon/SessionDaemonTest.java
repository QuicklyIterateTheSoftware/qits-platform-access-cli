package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.FakeIdp;
import eu.wohlben.qits.cli.access.FakeTime;
import eu.wohlben.qits.cli.access.LockHolder;
import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class SessionDaemonTest {

    private static final Instant T0 = Instant.parse("2026-09-11T20:00:00Z");
    private static final Duration MARGIN = Duration.ofSeconds(30);
    private static final String ENDED = "session ended (revoked or expired) — run `qits login`";

    @TempDir
    Path home;

    private FakeIdp idp;
    private FakeTime time;
    private SessionFile store;
    private final ByteArrayOutputStream log = new ByteArrayOutputStream();
    /** When each refresh request reached the idp, by the fake clock. */
    private final List<Instant> refreshedAt = new ArrayList<>();

    @BeforeEach
    void start() throws Exception {
        idp = new FakeIdp();
        idp.onRequest = () -> refreshedAt.add(time.instant());
        time = new FakeTime(T0);
        store = new SessionFile(home.resolve("qits"));
    }

    @AfterEach
    void stop() {
        idp.close();
        assertThat(log()).doesNotContain(FakeIdp.SECRET);
        // One line per event, each with a time.
        assertThat(log().lines()).allMatch(l -> l.matches("^\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d.*qits session-daemon: .*"));
    }

    private String log() {
        return log.toString(StandardCharsets.UTF_8);
    }

    private SessionDaemon daemon() {
        return daemon(TokenClient::new);
    }

    private SessionDaemon daemon(Function<String, TokenClient> idpFor) {
        return new SessionDaemon(store, MARGIN, time, time, idpFor, new PrintStream(log, true, StandardCharsets.UTF_8));
    }

    private String seed(Instant accessExpiresAt, String refreshToken, Instant refreshExpiresAt) throws Exception {
        store.write(new Session(idp.url(), "qits-cli", FakeIdp.SECRET + "old-access", accessExpiresAt,
                refreshToken, refreshExpiresAt));
        return refreshToken;
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    @Test
    void refreshesAtExpiryMinusTheMargin() throws Exception {
        String old = seed(T0.plusSeconds(900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        SessionDaemon daemon = daemon();
        time.afterSleep = () -> {
            if (idp.grants("refresh_token") > 0) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(refreshedAt).containsExactly(T0.plusSeconds(870));
        Session now = store.read().orElseThrow();
        assertThat(now.refreshToken()).isNotEqualTo(old);
        assertThat(now.accessExpiresAt()).isEqualTo(T0.plusSeconds(870 + 900));
        assertThat(idp.requests.getFirst()).containsEntry("grant_type", "refresh_token")
                .containsEntry("client_id", "qits-cli").containsEntry("refresh_token", old);
        assertThat(time.sleeps).allMatch(d -> d.compareTo(Duration.ofSeconds(60)) <= 0);
        assertThat(log()).contains("refreshed; access token valid until").contains("stopped");
    }

    /** A suspend: the wall clock jumps past the expiry during one sleep, and the refresh follows at once. */
    @Test
    void aClockJumpPastTheExpiryRefreshesAtOnce() throws Exception {
        seed(T0.plusSeconds(900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        SessionDaemon daemon = daemon();
        AtomicInteger sleepsBeforeRefresh = new AtomicInteger(-1);
        idp.onRequest = () -> {
            refreshedAt.add(time.instant());
            sleepsBeforeRefresh.compareAndSet(-1, time.sleeps.size());
        };
        time.afterSleep = () -> {
            if (time.sleeps.size() == 1) {
                time.jump(Duration.ofHours(2));
            }
            if (idp.grants("refresh_token") > 0) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(sleepsBeforeRefresh).hasValue(1);
        assertThat(refreshedAt.getFirst()).isEqualTo(T0.plus(SessionDaemon.MAX_STEP).plus(Duration.ofHours(2)));
        assertThat(log()).doesNotContain(ENDED);
    }

    @Test
    void anExpiredAccessTokenIsRefreshedStraightAway() throws Exception {
        seed(T0.minus(Duration.ofHours(3)), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        SessionDaemon daemon = daemon();
        time.afterSleep = daemon::stop;

        assertThat(daemon.run()).isZero();

        assertThat(refreshedAt).containsExactly(T0);
        assertThat(store.read().orElseThrow().accessExpiresAt()).isEqualTo(T0.plusSeconds(900));
    }

    @Test
    void invalidGrantEndsTheSessionAndWaitsForANewFile() throws Exception {
        seed(T0.minusSeconds(60), FakeIdp.SECRET + "revoked", T0.plus(Duration.ofDays(30)));
        SessionDaemon daemon = daemon();
        AtomicInteger polls = new AtomicInteger();
        time.afterSleep = () -> {
            if (polls.incrementAndGet() == 5) {
                try {
                    // A person runs `qits login`.
                    seed(T0.minusSeconds(1), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            if (log().contains("refreshed;")) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(count(log(), ENDED)).isEqualTo(1);
        assertThat(log()).contains("refresh token is invalid").contains("session file changed").contains("refreshed;");
        assertThat(idp.grants("refresh_token")).isEqualTo(2);
        // While it waited it polled the file and sent nothing.
        assertThat(time.sleeps.subList(0, 4)).containsOnly(SessionDaemon.POLL);
    }

    /** A login during the sleep replaced the file; the daemon reads it again under the lock. */
    @Test
    void aReplacedFileIsReadAgainBeforeRefreshing() throws Exception {
        String first = seed(T0.plusSeconds(900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        String[] second = new String[1];
        SessionDaemon daemon = daemon();
        time.afterSleep = () -> {
            if (second[0] == null && !time.instant().isBefore(T0.plusSeconds(600))) {
                try {
                    second[0] = seed(T0.plusSeconds(600 + 900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            if (idp.grants("refresh_token") > 0) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(refreshedAt).containsExactly(T0.plusSeconds(600 + 900 - 30));
        assertThat(idp.requests.getFirst()).containsEntry("refresh_token", second[0]);
        assertThat(idp.requests).noneMatch(r -> first.equals(r.get("refresh_token")));
    }

    /** Connection errors and 5xx retry with a doubling wait, never more than 60 s. */
    @Test
    void networkTroubleBacksOff() throws Exception {
        seed(T0.minusSeconds(1), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
        idp.failNext(503, 503, 503, 503, 503, 503, 503, 503);
        String dead;
        try (ServerSocket closed = new ServerSocket(0)) {
            dead = "http://127.0.0.1:" + closed.getLocalPort() + "/idp";
        }
        List<Instant> attempts = new ArrayList<>();
        SessionDaemon daemon = daemon(url -> {
            attempts.add(time.instant());
            // The first two tries find nothing listening at all.
            return new TokenClient(attempts.size() <= 2 ? dead : url);
        });
        time.afterSleep = () -> {
            if (log().contains("refreshed;")) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < attempts.size(); i++) {
            gaps.add(Duration.between(attempts.get(i - 1), attempts.get(i)).toSeconds());
        }
        assertThat(gaps).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L, 60L);
        assertThat(log()).contains("cannot reach").contains("HTTP 503").contains("trying again in 60 s")
                .contains("refreshed;").doesNotContain(ENDED);
    }

    @Test
    void retriesStopAtTheSessionEnd() throws Exception {
        seed(T0.minusSeconds(1), idp.issueRefreshToken(), T0.plusSeconds(10));
        for (int i = 0; i < 50; i++) {
            idp.failNext(503);
        }
        SessionDaemon daemon = daemon();
        time.afterSleep = () -> {
            if (log().contains(ENDED)) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(count(log(), ENDED)).isEqualTo(1);
        assertThat(log()).contains("has passed");
        assertThat(time.instant()).isBefore(T0.plusSeconds(20));
    }

    @Test
    void noSessionWaitsForOne() throws Exception {
        SessionDaemon daemon = daemon();
        AtomicInteger polls = new AtomicInteger();
        time.afterSleep = () -> {
            if (polls.incrementAndGet() == 3) {
                try {
                    seed(T0.plusSeconds(900), idp.issueRefreshToken(), T0.plus(Duration.ofDays(30)));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            if (log().contains("access token valid until")) {
                daemon.stop();
            }
        };

        assertThat(daemon.run()).isZero();

        assertThat(count(log(), "no session at " + store.path())).isEqualTo(1);
        assertThat(log()).contains("session file changed");
    }

    /** The lifetime lock is an fcntl lock, so the other daemon is a real second process. */
    @Test
    void theLockKeepsASecondDaemonOut() throws Exception {
        Files.createDirectories(store.directory());
        Process other = LockHolder.start(store.daemonLockPath());
        try {
            assertThat(daemon().run()).isEqualTo(1);
            assertThat(log()).contains("another qits session-daemon holds " + store.daemonLockPath());
        } finally {
            other.getOutputStream().close();
            other.waitFor();
        }

        // Once the other one is gone, a daemon starts, and on a stop it lets the lock go again.
        SessionDaemon daemon = daemon();
        time.afterSleep = daemon::stop;
        assertThat(daemon.run()).isZero();
        Optional<ExclusiveLock> free = ExclusiveLock.tryAcquire(store.daemonLockPath());
        assertThat(free).isPresent();
        free.get().close();
    }

    @Test
    void theLockKeepsASecondDaemonOutInTheSameProcess() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        Sleeper blocking = new Sleeper() {
            @Override
            public void sleep(Duration duration) throws InterruptedException {
                stopped.await();
            }

            @Override
            public void wake() {
                stopped.countDown();
            }
        };
        ByteArrayOutputStream firstLog = new ByteArrayOutputStream();
        SessionDaemon first = new SessionDaemon(store, MARGIN, time, blocking, TokenClient::new,
                new PrintStream(firstLog, true, StandardCharsets.UTF_8));
        int[] firstExit = {-1};
        Thread running = Thread.ofPlatform().start(() -> {
            try {
                firstExit[0] = first.run();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        while (!firstLog.toString(StandardCharsets.UTF_8).contains("no session")) {
            Thread.sleep(10);
        }

        assertThat(daemon().run()).isEqualTo(1);

        first.stop();
        running.join();
        assertThat(firstExit[0]).isZero();
    }
}
