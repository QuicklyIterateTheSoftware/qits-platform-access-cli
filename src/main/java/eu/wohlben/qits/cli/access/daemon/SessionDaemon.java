package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.ExclusiveLock;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.access.session.SessionRefresh;
import eu.wohlben.qits.cli.access.session.SessionRefresh.Outcome;
import eu.wohlben.qits.cli.access.session.Times;

import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * Keeps {@code t.json} valid for as long as it runs.
 * <p>
 * <b>Refresh tokens rotate, and the idp revokes the whole family when a spent one comes back</b>
 * — an honest race between two refreshers included. So every refresh and every write happens
 * under the write lock, the file is read again under it, and a second daemon is kept out by the
 * lifetime lock (see {@link SessionFile}).
 * <p>
 * <b>Wall clock, short sleeps.</b> WSL and laptops suspend, and WSL's clock jumps on resume. So the
 * daemon sleeps in short steps and reads the wall clock after each: a resume past the expiry
 * refreshes at once instead of sleeping on. An expired access token is not an error while the
 * refresh token is valid.
 * <p>
 * Logs one line per event to stderr, with a time. Never a token.
 */
public final class SessionDaemon {

    /** The longest single sleep. The epic allows up to 60 s; shorter costs nothing. */
    static final Duration MAX_STEP = Duration.ofSeconds(15);
    /** How often a waiting daemon looks for a new session file. */
    static final Duration POLL = Duration.ofSeconds(2);
    static final Duration FIRST_BACKOFF = Duration.ofSeconds(1);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final SessionFile store;
    private final Duration margin;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Function<String, TokenClient> idpFor;
    private final PrintStream log;

    private volatile boolean stopRequested;
    private Instant announced;

    public SessionDaemon(SessionFile store, Duration margin, Clock clock, Sleeper sleeper,
                         Function<String, TokenClient> idpFor, PrintStream log) {
        this.store = store;
        this.margin = margin;
        this.clock = clock;
        this.sleeper = sleeper;
        this.idpFor = idpFor;
        this.log = log;
    }

    /** Asks the loop to end. It releases both locks on the way out. */
    public void stop() {
        stopRequested = true;
        sleeper.wake();
    }

    /** 0 after a stop, 1 when another daemon holds the lifetime lock. */
    public int run() throws IOException, InterruptedException {
        Optional<ExclusiveLock> lifetime = store.tryLockForDaemon();
        if (lifetime.isEmpty()) {
            log("another qits session-daemon holds " + store.daemonLockPath() + "; exiting");
            return 1;
        }
        try (ExclusiveLock ignored = lifetime.get()) {
            log("started; session file " + store.path() + ", refresh " + margin.toSeconds() + " s before expiry");
            loop();
            log("stopped");
            return 0;
        }
    }

    private void loop() throws InterruptedException {
        Duration backoff = FIRST_BACKOFF;
        while (!stopRequested) {
            SessionFile.Fingerprint seen = store.fingerprint();
            Optional<Session> read;
            try {
                read = store.read();
            } catch (IOException unreadable) {
                log(unreadable.getMessage() + "; waiting for a new session file");
                waitForChange(seen);
                continue;
            }
            if (read.isEmpty()) {
                log("no session at " + store.path() + "; waiting for `qits login`");
                waitForChange(seen);
                continue;
            }
            Session session = read.get();
            if (!clock.instant().isBefore(session.refreshExpiresAt())) {
                ended("the session end " + Times.local(session.refreshExpiresAt()) + " has passed");
                waitForChange(seen);
                continue;
            }
            Instant due = session.accessExpiresAt().minus(margin);
            if (!session.accessExpiresAt().equals(announced)) {
                announced = session.accessExpiresAt();
                log("access token valid until " + Times.local(session.accessExpiresAt())
                        + "; next refresh at " + Times.local(due));
            }
            if (!sleepUntil(due)) {
                return;
            }

            Outcome outcome = SessionRefresh.refreshIfDue(store, margin, clock, idpFor);
            switch (outcome) {
                case Outcome.Refreshed r -> {
                    Session next = r.session();
                    announced = next.accessExpiresAt();
                    log("refreshed; access token valid until " + Times.local(next.accessExpiresAt())
                            + ", session ends " + Times.local(next.refreshExpiresAt())
                            + "; next refresh at " + Times.local(next.accessExpiresAt().minus(margin)));
                    backoff = FIRST_BACKOFF;
                }
                case Outcome.NotDue r -> {
                    // A login or a platform command replaced the file. The next pass reads it again.
                }
                case Outcome.NoSession r -> {
                    // The file was removed. The next pass says so and waits.
                }
                case Outcome.Unreadable u -> log(u.detail());
                case Outcome.Ended e -> {
                    ended(e.detail());
                    waitForChange(e.seen());
                    backoff = FIRST_BACKOFF;
                }
                case Outcome.Retry r -> {
                    // Network trouble means wait, not fail: try again until the session end. The
                    // next pass sees the session end if it comes first.
                    log(r.detail() + "; trying again in " + backoff.toSeconds() + " s");
                    Instant until = clock.instant().plus(backoff);
                    if (!sleepUntil(until.isBefore(r.sessionEnd()) ? until : r.sessionEnd())) {
                        return;
                    }
                    backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
                }
            }
        }
    }

    private void ended(String detail) {
        log("session ended (revoked or expired) — run `qits login` (" + detail + ")");
        announced = null;
    }

    /** False when a stop came first. */
    private boolean sleepUntil(Instant target) throws InterruptedException {
        while (!stopRequested) {
            Duration left = Duration.between(clock.instant(), target);
            if (left.isNegative() || left.isZero()) {
                return true;
            }
            sleeper.sleep(left.compareTo(MAX_STEP) > 0 ? MAX_STEP : left);
        }
        return false;
    }

    private void waitForChange(SessionFile.Fingerprint seen) throws InterruptedException {
        while (!stopRequested) {
            sleeper.sleep(POLL);
            if (!store.fingerprint().equals(seen)) {
                log("session file changed");
                return;
            }
        }
    }

    private void log(String message) {
        log.println(Times.stamp(clock.instant()) + " qits session-daemon: " + message);
        log.flush();
    }
}
