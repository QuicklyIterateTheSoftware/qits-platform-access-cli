package eu.wohlben.qits.cli.access.session;

import eu.wohlben.qits.cli.access.idp.IdpException;
import eu.wohlben.qits.cli.access.idp.TokenClient;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * One refresh of {@code t.json}: under the write lock, with the file read again first.
 * <p>
 * <b>The only place a refresh token is spent.</b> The daemon and every platform command call this,
 * so the rules live once: the lock, the second read (the other process may have refreshed while
 * this one waited), and the write straight after the idp answers.
 */
public final class SessionRefresh {

    private SessionRefresh() {
    }

    /** What one attempt came to. No variant holds a token in its {@code toString}. */
    public sealed interface Outcome {
        /** The idp answered and the new pair is written. */
        record Refreshed(Session session) implements Outcome {
        }

        /** Not due as the file stands now: another process refreshed, or a login replaced it. */
        record NotDue(Session session) implements Outcome {
        }

        /** There is no session file now. */
        record NoSession() implements Outcome {
        }

        /** The session file or its lock cannot be read. */
        record Unreadable(String detail) implements Outcome {
        }

        /** The session is over: refused, expired, or the new pair could not be written. */
        record Ended(SessionFile.Fingerprint seen, String detail) implements Outcome {
        }

        /** A connection error or a 5xx. Worth another try before {@code sessionEnd}. */
        record Retry(String detail, Instant sessionEnd) implements Outcome {
        }
    }

    /**
     * Refreshes when the access token has less than {@code margin} left, as the file reads under
     * the lock.
     */
    public static Outcome refreshIfDue(SessionFile store, Duration margin, Clock clock,
                                       Function<String, TokenClient> idpFor) throws InterruptedException {
        try (ExclusiveLock ignored = store.lockForWrite()) {
            SessionFile.Fingerprint seen = store.fingerprint();
            Optional<Session> read = store.read();
            if (read.isEmpty()) {
                return new Outcome.NoSession();
            }
            Session session = read.get();
            Instant now = clock.instant();
            if (now.isBefore(session.accessExpiresAt().minus(margin))) {
                return new Outcome.NotDue(session);
            }
            if (!now.isBefore(session.refreshExpiresAt())) {
                return new Outcome.Ended(seen, "the session end " + Times.local(session.refreshExpiresAt()) + " has passed");
            }
            TokenClient.TokenResponse token;
            try {
                token = idpFor.apply(session.idpUrl()).refresh(session.refreshToken());
            } catch (IdpException.Unreachable e) {
                return new Outcome.Retry(e.getMessage(), session.refreshExpiresAt());
            } catch (IdpException invalidGrantOrRefused) {
                // Presenting the same token again cannot help, and could be a replay.
                return new Outcome.Ended(seen, invalidGrantOrRefused.getMessage());
            }
            // Write first. The idp has spent the old refresh token; a crash before this write
            // loses the session.
            Session next = Session.from(session.idpUrl(), token, clock.instant());
            try {
                store.write(next);
            } catch (IOException e) {
                return new Outcome.Ended(seen, "cannot write the new session: " + e.getMessage());
            }
            return new Outcome.Refreshed(next);
        } catch (IOException unreadable) {
            return new Outcome.Unreadable(unreadable.getMessage());
        }
    }
}
