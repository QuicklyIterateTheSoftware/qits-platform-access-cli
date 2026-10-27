package eu.wohlben.qits.cli.access.daemon;

import eu.wohlben.qits.cli.access.session.Session;

import java.time.Duration;

/**
 * What else the session daemon keeps fresh with the session: today the registry logins of
 * {@code qits registry-login}. The daemon calls it each time the session file holds a new access
 * token, its own refresh or another process's alike.
 */
public interface SessionListener {

    SessionListener NONE = new SessionListener() {
        @Override
        public String accessTokenChanged(Session session) {
            return null;
        }

        @Override
        public Duration minimumMargin() {
            return Duration.ZERO;
        }
    };

    /**
     * Takes the new access token. Returns a line for the daemon's log, or null for nothing to say.
     * Never puts a token in it.
     */
    String accessTokenChanged(Session session) throws Exception;

    /**
     * The daemon refreshes at least this long before expiry. A file other programs read the token
     * from needs it to stay good while they run.
     */
    Duration minimumMargin();
}
