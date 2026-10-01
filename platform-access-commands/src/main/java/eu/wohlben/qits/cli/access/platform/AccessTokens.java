package eu.wohlben.qits.cli.access.platform;

import eu.wohlben.qits.cli.access.idp.TokenClient;
import eu.wohlben.qits.cli.access.session.Session;
import eu.wohlben.qits.cli.access.session.SessionFile;
import eu.wohlben.qits.cli.access.session.SessionRefresh;
import eu.wohlben.qits.cli.access.session.SessionRefresh.Outcome;
import eu.wohlben.qits.cli.access.session.TokenClaims;
import eu.wohlben.qits.cli.session.Credential;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;

/**
 * The session a platform call uses, with an access token that is still good.
 * <p>
 * Normally {@code qits session-daemon} keeps the token fresh and this only reads the file. Without
 * a daemon, a token that expires within {@link #MARGIN} is refreshed here, through {@link
 * SessionRefresh}: under the write lock and after a second read, so a refresh the daemon (or
 * another command) made meanwhile is used instead of spending the refresh token twice.
 */
public final class AccessTokens implements Credential {

    /** The daemon's default margin: a token this close to its end may expire on the way. */
    public static final Duration MARGIN = Duration.ofSeconds(30);

    private static final String NOT_SIGNED_IN = "Not signed in — run `qits login`.";
    private static final String ENDED = "Session ended — run `qits login`.";

    private final SessionFile store;
    private final Clock clock;
    private final Function<String, TokenClient> idpFor;

    public AccessTokens(SessionFile store, Clock clock, Function<String, TokenClient> idpFor) {
        this.store = store;
        this.clock = clock;
        this.idpFor = idpFor;
    }

    @Override
    public String bearer() throws CliFailure, InterruptedException {
        return session().accessToken();
    }

    @Override
    public String who() throws CliFailure, InterruptedException {
        return TokenClaims.of(bearer()).who().map(name -> "signed in as " + name).orElse("signed in");
    }

    /** The session, refreshed first when its access token is about to expire. */
    public Session session() throws CliFailure, InterruptedException {
        Optional<Session> read;
        try {
            read = store.read();
        } catch (IOException unreadable) {
            throw new CliFailure(unreadable.getMessage(), CliFailure.FAILED);
        }
        if (read.isEmpty()) {
            throw new CliFailure(NOT_SIGNED_IN, CliFailure.USAGE);
        }
        Session session = read.get();
        Instant now = clock.instant();
        if (!now.isBefore(session.refreshExpiresAt())) {
            throw new CliFailure(ENDED, CliFailure.USAGE);
        }
        if (now.isBefore(session.accessExpiresAt().minus(MARGIN))) {
            return session;
        }
        Outcome outcome = SessionRefresh.refreshIfDue(store, MARGIN, clock, idpFor);
        return switch (outcome) {
            case Outcome.Refreshed r -> r.session();
            case Outcome.NotDue n -> n.session();
            case Outcome.NoSession n -> throw new CliFailure(NOT_SIGNED_IN, CliFailure.USAGE);
            case Outcome.Unreadable u -> throw new CliFailure(u.detail(), CliFailure.FAILED);
            case Outcome.Ended e -> throw new CliFailure(ENDED + " (" + e.detail() + ")", CliFailure.USAGE);
            case Outcome.Retry r -> throw CliFailure.retryable("Cannot refresh the session: " + r.detail());
        };
    }
}
